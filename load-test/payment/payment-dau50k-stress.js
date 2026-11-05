import { session } from './payment-session.js';

// 스트레스 (새 기준): 부하의 10배(분당 1,000세션 = 결제 약 35 RPS)를 예열 뒤 5분 보내 버티는지 본다
// 예열은 목표 속도(분당 100세션). VU도 부하의 10배
const TARGET_SESSIONS_PER_MINUTE = 100;
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
        // 예열은 분당 100세션 × 2분 = 200건. 여유를 두고 1,000번째 주문부터 쓴다
        stress: arrival(TARGET_SESSIONS_PER_MINUTE * 10, VUS * 10, '5m', WARMUP, 1000),
    },
    // 기준으로 멈추지 않는다. 측정 구간만 따로 보려고 항상 통과하는 기준을 둔다
    thresholds: {
        'http_req_failed{kind:payment,scenario:stress}': ['rate>=0'],
        'http_req_duration{kind:payment,scenario:stress}': ['max>=0'],
        'checks{scenario:stress}': ['rate>=0'],
        'http_req_duration{name:getPaymentMethods,scenario:stress}': ['max>=0'],
        'http_req_duration{name:retryPayment,scenario:stress}': ['max>=0'],
        'http_req_duration{name:confirmPayment,scenario:stress}': ['max>=0'],
        'http_req_duration{name:payWithPaymentMethod,scenario:stress}': ['max>=0'],
        'dropped_iterations{scenario:stress}': ['count>=0'],
    },
};

export { session };