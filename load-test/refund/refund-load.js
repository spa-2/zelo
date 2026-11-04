import { session, TARGET_SESSIONS_PER_100S } from './refund-session.js';

// 부하: 목표 트래픽(100초에 1건 = 환불 0.01 RPS)을 5분 동안 보내 기준을 통과하는지 본다
// VU = 목표 RPS × 스모크 p95(초), 반올림. 스모크 결과로 계산해 -e VUS=로 넘긴다
const VUS = Number(__ENV.VUS || 1);

export const options = {
    scenarios: {
        load: {
            executor: 'constant-arrival-rate',
            rate: TARGET_SESSIONS_PER_100S,
            timeUnit: '100s',
            duration: '5m',
            preAllocatedVUs: VUS,
            maxVUs: VUS,
            exec: 'session',
            gracefulStop: '30s',
        },
    },
    // p95 기준은 팀 합격 기준이 정해지면 바꾼다
    thresholds: {
        'http_req_failed{kind:refund}': ['rate<0.01'],
        'http_req_duration{kind:refund}': ['p(95)<1000'],
        checks: ['rate>0.99'],
    },
};

export { session };