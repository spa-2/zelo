import { session, TARGET_SESSIONS_PER_MINUTE } from './order-session.js';

// 스트레스: 부하 테스트의 10배(세션 분당 50개 = 주문 2.5 RPS, VU도 10배)를 5분 동안 보내 무너지는 지점과 원인을 본다
// VU가 모자라 못 보낸 세션은 dropped_iterations로 남는다
const VUS = Number(__ENV.VUS || 1) * 10;

export const options = {
    scenarios: {
        stress: {
            executor: 'constant-arrival-rate',
            rate: TARGET_SESSIONS_PER_MINUTE * 10,
            timeUnit: '1m',
            duration: '5m',
            preAllocatedVUs: VUS,
            maxVUs: VUS,
            exec: 'session',
            gracefulStop: '30s',
        },
    },
    // 한계를 찾는 테스트라 기준으로 멈추지 않는다. 요약에 줄이 나오게 항상 통과하는 기준을 둔다
    thresholds: {
        'http_req_failed{kind:order}': ['rate>=0'],
        'http_req_duration{kind:order}': ['max>=0'],
        'http_req_duration{name:createConfirmation}': ['max>=0'],
        'http_req_duration{name:createOrder}': ['max>=0'],
        'http_req_duration{name:getOrderDetail}': ['max>=0'],
    },
};

export { session };
