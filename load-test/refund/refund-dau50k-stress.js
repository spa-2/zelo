import { session } from './refund-session.js';

// 스트레스 (새 기준): 부하의 10배(분당 500세션 = 환불 약 8.3 RPS)를 예열 뒤 5분 보내 버티는지 본다
// 예열은 목표 속도(분당 50세션). VU도 부하의 10배
const TARGET_SESSIONS_PER_MINUTE = 50;
const WARMUP = __ENV.WARMUP || '2m';
const VUS = Number(__ENV.VUS || 2);

const arrival = (rate, vus, duration, startTime, offset) => ({
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1m',
    duration,
    startTime,
    preAllocatedVUs: Math.max(5, vus * 2),
    maxVUs: vus * 10,
    exec: 'session',
    gracefulStop: '30s',
    env: { ORDER_OFFSET: String(offset) },
});

export const options = {
    scenarios: {
        warmup: arrival(TARGET_SESSIONS_PER_MINUTE, VUS, WARMUP, '0s', 0),
        // 예열은 100건. 여유를 두고 500번째 주문부터 쓴다 (측정 2,500건)
        stress: arrival(TARGET_SESSIONS_PER_MINUTE * 10, VUS * 10, '5m', WARMUP, 500),
    },
    // 기준으로 멈추지 않는다. 측정 구간만 따로 보려고 항상 통과하는 기준을 둔다
    thresholds: {
        'http_req_failed{kind:refund,scenario:stress}': ['rate>=0'],
        'http_req_duration{kind:refund,scenario:stress}': ['max>=0'],
        'checks{scenario:stress}': ['rate>=0'],
        'http_req_duration{name:fullRefund,scenario:stress}': ['max>=0'],
        'http_req_duration{name:partialRefund,scenario:stress}': ['max>=0'],
        'dropped_iterations{scenario:stress}': ['count>=0'],
    },
};

export { session };