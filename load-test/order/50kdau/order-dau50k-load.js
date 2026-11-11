import { session } from '../practice/order-session.js';

// 부하 (새 기준): 피크 1시간 1만 명 중 40% 주문 → 4,000 ÷ 3,600초 × 1.5 = 분당 100세션 (주문 요청 약 5 RPS)
// 예열(측정 제외) 뒤 5분 측정. 기준은 측정 구간(scenario:load)에만 건다
const TARGET_SESSIONS_PER_MINUTE = 100;
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
    env: { USER_OFFSET: String(offset) },
});

export const options = {
    scenarios: {
        warmup: arrival(WARMUP, '0s', 0),
        // 예열은 분당 100세션 × 2분 = 200명. 여유를 두고 1,000번째 사용자부터 쓴다
        load: arrival('5m', WARMUP, 1000),
    },
    // p95 기준은 팀 합격 기준이 정해지면 바꾼다
    thresholds: {
        'http_req_failed{kind:order,scenario:load}': ['rate<0.01'],
        'http_req_duration{kind:order,scenario:load}': ['p(95)<1000'],
        'checks{scenario:load}': ['rate>0.99'],
        'http_req_duration{name:createConfirmation,scenario:load}': ['max>=0'],
        'http_req_duration{name:createOrder,scenario:load}': ['max>=0'],
        'http_req_duration{name:getOrderDetail,scenario:load}': ['max>=0'],
        // 못 보낸 세션이 있으면 목표를 못 버틴 것으로 본다
        'dropped_iterations{scenario:load}': ['count==0'],
    },
};

export { session };