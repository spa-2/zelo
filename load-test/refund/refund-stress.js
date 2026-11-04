import { session, TARGET_SESSIONS_PER_100S } from './refund-session.js';

// 스트레스: 부하 테스트의 10배(10초에 1건 = 환불 0.1 RPS, VU도 10배)를 5분 동안 보내 무너지는 지점과 원인을 본다
// VU가 모자라 못 보낸 세션은 dropped_iterations로 남는다
const VUS = Number(__ENV.VUS || 1) * 10;

export const options = {
    scenarios: {
        stress: {
            executor: 'constant-arrival-rate',
            rate: TARGET_SESSIONS_PER_100S * 10,
            timeUnit: '100s',
            duration: '5m',
            preAllocatedVUs: VUS,
            maxVUs: VUS,
            exec: 'session',
            gracefulStop: '30s',
        },
    },
    // 한계를 찾는 테스트라 기준으로 멈추지 않는다. 환불 요청만 따로 보려고 항상 통과하는 기준을 둔다
    thresholds: {
        'http_req_failed{kind:refund}': ['rate>=0'],
        'http_req_duration{kind:refund}': ['max>=0'],
    },
};

export { session };