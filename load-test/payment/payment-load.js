import { session, TARGET_SESSIONS_PER_MINUTE } from './payment-session.js';

// 부하: 목표 트래픽(세션 분당 5개 = 결제 0.2 RPS)을 5분 동안 보내 기준을 통과하는지 본다
// VU = 목표 RPS × 스모크 p95(초), 반올림. 스모크 결과로 계산해 -e VUS=로 넘긴다
const VUS = Number(__ENV.VUS || 1);

export const options = {
    scenarios: {
        load: {
            executor: 'constant-arrival-rate',
            rate: TARGET_SESSIONS_PER_MINUTE,
            timeUnit: '1m',
            duration: '5m',
            preAllocatedVUs: VUS,
            maxVUs: VUS,
            exec: 'session',
            gracefulStop: '30s',
        },
    },
    // p95 기준은 팀 합격 기준이 정해지면 바꾼다
    thresholds: {
        'http_req_failed{kind:payment}': ['rate<0.01'],
        'http_req_duration{kind:payment}': ['p(95)<1000'],
        checks: ['rate>0.99'],
    },
};

export { session };
