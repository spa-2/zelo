import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

// 한계 (새 기준): 목표(분당 100세션 = 초당 1.67세션)의 10·50·100·150·200·250배로 올려 처음 기준을 넘는 단계를 찾는다
// 단계마다 60초. 단계를 시나리오로 나눠 기준을 단계별로 본다 (처음부터 누적한 p95로 보면 늦게 걸린다)
// 로그인(비밀번호 확인)이 먼저 병목이 되지 않도록 setup에서 결제 범위 사용자 토큰을 미리 받아 두고, 세션에서는 결제 요청만 보낸다
// 모든 단계를 다 돌면 약 7.6만 세션. 시드 주문 10만 건 안에 들어온다
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TARGET_SESSIONS_PER_SECOND = 100 / 60;
const MULTIPLIERS = (__ENV.MULTIPLIERS || '10,50,100,150,200,250').split(',').map(Number);
const STAGE_SECONDS = Number(__ENV.STAGE_SECONDS || 60);
const USER_FROM = 20001;
const USER_COUNT = 5000;
const EMAIL_USER_MAX = 21000;
const PASSWORD = 'Zelo1234!';
const LOGIN_BATCH = 20;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const orders = new SharedArray('payment-orders', () => open('./data/payment-orders.csv').trim().split('\n').slice(1)
    .map((line) => {
        const [orderId, userId, portoneId, status] = line.trim().split(',');
        return { orderId: Number(orderId), userId: Number(userId), portoneId, status };
    }));

const scenarios = {};
const thresholds = {};
let offset = 0;
MULTIPLIERS.forEach((multiplier, index) => {
    const name = `x${multiplier}`;
    const rate = Math.round(TARGET_SESSIONS_PER_SECOND * multiplier);
    scenarios[name] = {
        executor: 'constant-arrival-rate',
        rate,
        timeUnit: '1s',
        duration: `${STAGE_SECONDS}s`,
        startTime: `${STAGE_SECONDS * index}s`,
        // 서버가 느려져도 k6가 속도를 유지해야 서버 쪽 한계를 볼 수 있다
        preAllocatedVUs: Math.max(10, rate),
        maxVUs: Math.max(30, rate * 3),
        exec: 'session',
        gracefulStop: '10s',
        // 단계마다 번호가 0부터 시작하므로 앞 단계가 쓴 주문 수만큼 띄운다
        env: { ORDER_OFFSET: String(offset) },
    };
    // k6가 단계마다 1건을 더 돌 수 있어 10건 여유를 둔다
    offset += rate * STAGE_SECONDS + 10;
    // 에러가 쏟아질 때만 멈춘다
    thresholds[`http_req_failed{kind:payment,scenario:${name}}`] = [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '10s' }];
    // p95로는 멈추지 않고 단계별 결과로 판정한다
    thresholds[`http_req_duration{kind:payment,scenario:${name}}`] = ['p(95)<2000'];
    thresholds[`http_reqs{kind:payment,scenario:${name}}`] = ['count>=0'];
    thresholds[`http_req_duration{name:confirmPayment,scenario:${name}}`] = ['max>=0'];
    thresholds[`http_req_duration{name:payWithPaymentMethod,scenario:${name}}`] = ['max>=0'];
    thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
});

export const options = { setupTimeout: '10m', scenarios, thresholds };

// 결제 범위 사용자 전부 미리 로그인. 한 번에 LOGIN_BATCH명씩 보낸다
export function setup() {
    const tokens = {};
    for (let from = USER_FROM; from < USER_FROM + USER_COUNT; from += LOGIN_BATCH) {
        const userIds = [];
        for (let userId = from; userId < Math.min(from + LOGIN_BATCH, USER_FROM + USER_COUNT); userId++) {
            userIds.push(userId);
        }
        const responses = http.batch(userIds.map((userId) => loginRequest(userId)));
        responses.forEach((res, i) => {
            if (res.status !== 200) {
                throw new Error(`로그인 실패 userId=${userIds[i]} status=${res.status}`);
            }
            tokens[userIds[i]] = res.json('data.accessToken');
        });
    }
    // 서버 지표 CSV를 단계별로 나누려고 첫 단계가 시작하는 시각을 남긴다
    console.log(`첫 단계 시작 ${new Date().toISOString()}`);
    return { tokens };
}

function loginRequest(userId) {
    const params = { headers: JSON_HEADERS, tags: { name: 'login' } };
    return userId <= EMAIL_USER_MAX
        ? ['POST', `${BASE_URL}/api/users/login`,
            JSON.stringify({ email: `load${userId}@test.com`, password: PASSWORD }), params]
        : ['POST', `${BASE_URL}/api/auth/social/google/login`,
            JSON.stringify({ code: `fake-load-${userId}`, redirectUri: 'http://localhost' }), params];
}

export function session(data) {
    const order = orders[Number(__ENV.ORDER_OFFSET) + exec.scenario.iterationInTest];
    if (!order) {
        exec.test.abort('시드 주문을 다 씀');
    }
    const params = (name) => ({
        headers: { ...JSON_HEADERS, Authorization: `Bearer ${data.tokens[order.userId]}` },
        tags: { name, kind: 'payment' },
    });

    const methods = http.get(`${BASE_URL}/api/payment-methods`, params('getPaymentMethods'));
    check(methods, { '결제수단 조회 200': (r) => r.status === 200 });

    let portoneId = order.portoneId;
    if (order.status === 'FAILED') {
        const retry = http.post(`${BASE_URL}/api/payments`, JSON.stringify({ orderId: order.orderId }), params('retryPayment'));
        if (!check(retry, { '재결제 200': (r) => r.status === 200 })) {
            return;
        }
        portoneId = retry.json('data.portoneId');
    }

    const pay = order.orderId % 2 === 0 && methods.status === 200
        ? http.post(`${BASE_URL}/api/payments/${portoneId}/payment-method`,
            JSON.stringify({ paymentMethodId: methods.json('data.0.paymentMethodId') }), params('payWithPaymentMethod'))
        : http.post(`${BASE_URL}/api/payments/${portoneId}/confirm`, null, params('confirmPayment'));
    check(pay, { '결제 PAID': (r) => r.status === 200 && r.json('data.status') === 'PAID' });
}