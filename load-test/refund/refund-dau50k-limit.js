import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

// 한계 (새 기준): 목표(분당 50세션 = 초당 0.83세션)의 10·50·100·150·200·250·300배로 올려 처음 기준을 넘는 단계를 찾는다
// 단계마다 60초. 단계를 시나리오로 나눠 기준을 단계별로 본다
// 환불 범위 사용자(25,001~27,000, 전부 소셜)는 setup에서 미리 로그인한다. 소셜 로그인 fake 지연(900ms)이 먼저 병목이 되지 않게 한다
// 모든 단계를 다 돌면 약 5.3만 세션. 환불 시드 6만 건 안에 들어온다
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TARGET_SESSIONS_PER_SECOND = 50 / 60;
const MULTIPLIERS = (__ENV.MULTIPLIERS || '10,50,100,150,200,250,300').split(',').map(Number);
const STAGE_SECONDS = Number(__ENV.STAGE_SECONDS || 60);
const USER_FROM = 25001;
const USER_COUNT = 2000;
const LOGIN_BATCH = 20;
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
    thresholds[`http_req_failed{kind:refund,scenario:${name}}`] = [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '10s' }];
    // p95로는 멈추지 않고 단계별 결과로 판정한다
    thresholds[`http_req_duration{kind:refund,scenario:${name}}`] = ['p(95)<2000'];
    thresholds[`http_reqs{kind:refund,scenario:${name}}`] = ['count>=0'];
    thresholds[`http_req_duration{name:fullRefund,scenario:${name}}`] = ['max>=0'];
    thresholds[`http_req_duration{name:partialRefund,scenario:${name}}`] = ['max>=0'];
    thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
});

export const options = { setupTimeout: '10m', scenarios, thresholds };

// 환불 범위 사용자 전부 미리 로그인. 한 번에 LOGIN_BATCH명씩 보낸다
export function setup() {
    const tokens = {};
    for (let from = USER_FROM; from < USER_FROM + USER_COUNT; from += LOGIN_BATCH) {
        const userIds = [];
        for (let userId = from; userId < Math.min(from + LOGIN_BATCH, USER_FROM + USER_COUNT); userId++) {
            userIds.push(userId);
        }
        const responses = http.batch(userIds.map((userId) => ['POST', `${BASE_URL}/api/auth/social/google/login`,
            JSON.stringify({ code: `fake-load-${userId}`, redirectUri: 'http://localhost' }),
            { headers: JSON_HEADERS, tags: { name: 'login' } }]));
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

export function session(data) {
    const order = orders[Number(__ENV.ORDER_OFFSET) + exec.scenario.iterationInTest];
    if (!order) {
        exec.test.abort('시드 주문을 다 씀');
    }
    // 화면은 기본으로 남은 상품 전부를 고른다. 상품이 2개 이상인 주문의 절반은 첫 상품만 부분 환불한다 (가정값)
    const partial = order.items.length > 1 && order.orderId % 2 === 0;
    const items = partial ? order.items.slice(0, 1) : order.items;

    const res = http.post(`${BASE_URL}/api/orders/${order.orderId}/refund`, JSON.stringify({ reason: '부하 테스트 환불', items }), {
        headers: { ...JSON_HEADERS, Authorization: `Bearer ${data.tokens[order.userId]}` },
        tags: { name: partial ? 'partialRefund' : 'fullRefund', kind: 'refund' },
    });
    check(res, { '환불 COMPLETED': (r) => r.status === 200 && r.json('data.status') === 'COMPLETED' });
}