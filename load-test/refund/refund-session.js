import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

// refund-smoke.js, refund-load.js, refund-stress.js가 같이 쓰는 환불 1건의 흐름(세션)
// 세션 = 로그인 → 환불 요청. 로그인을 빼면 요청 1개
// 시드 주문(data/refund-orders.csv)을 앞에서부터 한 건씩 쓴다. 환불한 주문은 다시 못 쓰므로 단계마다 앱을 재시작해 시드를 새로 넣고 CSV를 다시 뽑는다
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 목표: 피크 2,000명 × 환불 2% × 1회 ÷ 7,200초 × 1.5 ≈ 0.01 RPS → 100초에 1건
export const TARGET_SESSIONS_PER_100S = 1;

// 사용자 시드에서 1~21,000은 이메일 가입, 그 뒤는 소셜 가입. 환불 범위(25,001~27,000)는 전부 소셜
const EMAIL_USER_MAX = 21000;
const PASSWORD = 'Zelo1234!';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// items 열은 "주문상품ID:수량;주문상품ID:수량"
const orders = new SharedArray('refund-orders', () => open('./data/refund-orders.csv').trim().split('\n').slice(1)
    .map((line) => {
        const [orderId, userId, items] = line.trim().split(',');
        return {
            orderId: Number(orderId),
            userId: Number(userId),
            items: items.split(';').map((item) => {
                const [orderItemId, quantity] = item.split(':');
                return { orderItemId: Number(orderItemId), quantity: Number(quantity) };
            }),
        };
    }));

export function session() {
    const order = orders[exec.scenario.iterationInTest];
    if (!order) {
        exec.test.abort('시드 주문을 다 씀');
    }
    const token = login(order.userId);
    if (!token) {
        return;
    }

    // 화면은 기본으로 남은 상품 전부를 고른다. 상품이 2개 이상인 주문의 절반은 첫 상품만 부분 환불한다 (가정값)
    const partial = order.items.length > 1 && order.orderId % 2 === 0;
    const items = partial ? order.items.slice(0, 1) : order.items;

    const res = http.post(`${BASE_URL}/api/orders/${order.orderId}/refund`, JSON.stringify({ reason: '부하 테스트 환불', items }), {
        headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` },
        tags: { name: partial ? 'partialRefund' : 'fullRefund', kind: 'refund' },
    });
    check(res, { '환불 COMPLETED': (r) => r.status === 200 && r.json('data.status') === 'COMPLETED' });
}

// 로그인은 환불 측정에서 빼려고 kind 태그를 붙이지 않는다
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