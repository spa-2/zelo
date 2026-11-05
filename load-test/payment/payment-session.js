import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

// payment-smoke.js, payment-load.js, payment-stress.js가 같이 쓰는 결제 1건의 흐름(세션)
// 세션 = 로그인 → 결제수단 조회 → (실패한 결제면 재결제) → 결제. 로그인을 빼면 요청 약 2.3개
// 시드 주문(data/payment-orders.csv)을 앞에서부터 한 건씩 쓴다. 결제한 주문은 다시 못 쓰므로 단계마다 앱을 재시작해 시드를 새로 넣고 CSV를 다시 뽑는다
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 목표: 피크 2,000명 × 결제 20% × 2.3회 ÷ 7,200초 × 1.5 = 0.2 RPS → 세션 분당 5개 (0.2 ÷ 2.3 × 60)
export const TARGET_SESSIONS_PER_MINUTE = 5;

// 사용자 시드에서 1~21,000은 이메일 가입, 그 뒤는 소셜 가입. 결제 범위(20,001~25,000)는 둘이 섞여 있다
const EMAIL_USER_MAX = 21000;
const PASSWORD = 'Zelo1234!';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const orders = new SharedArray('payment-orders', () => open('./data/payment-orders.csv').trim().split('\n').slice(1)
    .map((line) => {
        const [orderId, userId, portoneId, status] = line.trim().split(',');
        return { orderId: Number(orderId), userId: Number(userId), portoneId, status };
    }));

export function session() {
    // 시나리오마다 번호가 0부터 시작하므로, 시나리오가 여러 개면 env의 ORDER_OFFSET만큼 띄워서 시드 주문이 겹치지 않게 한다
    const order = orders[Number(__ENV.ORDER_OFFSET || 0) + exec.scenario.iterationInTest];
    if (!order) {
        exec.test.abort('시드 주문을 다 씀');
    }
    const token = login(order.userId);
    if (!token) {
        return;
    }
    const params = (name) => ({
        headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` },
        tags: { name, kind: 'payment' },
    });

    // 결제 화면에 들어오면 항상 등록 카드를 조회한다
    const methods = http.get(`${BASE_URL}/api/payment-methods`, params('getPaymentMethods'));
    check(methods, { '결제수단 조회 200': (r) => r.status === 200 });

    let portoneId = order.portoneId;
    if (order.status === 'FAILED') {
        // 실패한 결제는 재결제 API로 새 결제 ID를 받는다 (시드의 15%)
        const retry = http.post(`${BASE_URL}/api/payments`, JSON.stringify({ orderId: order.orderId }), params('retryPayment'));
        if (!check(retry, { '재결제 200': (r) => r.status === 200 })) {
            return;
        }
        portoneId = retry.json('data.portoneId');
    }

    // 일반 결제(결제창 → 확인)와 등록 카드 결제를 반씩 (가정값)
    const pay = order.orderId % 2 === 0
        ? http.post(`${BASE_URL}/api/payments/${portoneId}/payment-method`,
            JSON.stringify({ paymentMethodId: methods.json('data.0.paymentMethodId') }), params('payWithPaymentMethod'))
        : http.post(`${BASE_URL}/api/payments/${portoneId}/confirm`, null, params('confirmPayment'));
    check(pay, { '결제 PAID': (r) => r.status === 200 && r.json('data.status') === 'PAID' });
}

// 로그인은 결제 측정에서 빼려고 kind 태그를 붙이지 않는다
function login(userId) {
    const params = { headers: JSON_HEADERS, tags: { name: 'login' } };
    const res = userId <= EMAIL_USER_MAX
        ? http.post(`${BASE_URL}/api/users/login`,
            JSON.stringify({ email: `load${userId}@test.com`, password: PASSWORD }), params)
        : http.post(`${BASE_URL}/api/auth/social/google/login`,
            JSON.stringify({ code: `fake-load-${userId}`, redirectUri: 'http://localhost' }), params);
    check(res, { '로그인 200': (r) => r.status === 200 });
    return res.status === 200 ? res.json('data.accessToken') : null;
}
