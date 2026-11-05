import { session } from './refund-session.js';

// 부하 (새 기준): 결제 4,000명 중 50% 환불 → 2,000 ÷ 3,600초 × 1.5 = 분당 50세션 (환불 요청 약 0.83 RPS)
// 예열(측정 제외) 뒤 5분 측정. 기준은 측정 구간(scenario:load)에만 건다
// VU = 초당 세션(0.83) × 세션 시간(로그인 0.9초 + 환불 p95), 올림. -e VUS=로 넘긴다
const TARGET_SESSIONS_PER_MINUTE = 50;
const WARMUP = __ENV.WARMUP || '2m';
const VUS = Number(__ENV.VUS || 2);

const arrival = (duration, startTime, offset) => ({
    executor: 'constant-arrival-rate',
    rate: TARGET_SESSIONS_PER_MINUTE,
    timeUnit: '1m',
    duration,
    startTime,
    preAllocatedVUs: Math.max(5, VUS * 2),
    maxVUs: VUS * 10,
    exec: 'session',
    gracefulStop: '30s',
    env: { ORDER_OFFSET: String(offset) },
});

export const options = {
    scenarios: {
        warmup: arrival(WARMUP, '0s', 0),
        // 예열은 분당 50세션 × 2분 = 100건. 여유를 두고 500번째 주문부터 쓴다
        load: arrival('5m', WARMUP, 500),
    },
    // p95 기준은 팀 합격 기준이 정해지면 바꾼다
    thresholds: {
        'http_req_failed{kind:refund,scenario:load}': ['rate<0.01'],
        'http_req_duration{kind:refund,scenario:load}': ['p(95)<1000'],
        'checks{scenario:load}': ['rate>0.99'],
        'http_req_duration{name:fullRefund,scenario:load}': ['max>=0'],
        'http_req_duration{name:partialRefund,scenario:load}': ['max>=0'],
        // 못 보낸 세션이 있으면 목표를 못 버틴 것으로 본다
        'dropped_iterations{scenario:load}': ['count==0'],
    },
};

export { session };