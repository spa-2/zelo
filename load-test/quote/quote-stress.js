import { session, TARGET_SESSIONS_PER_MINUTE } from './quote-session.js';
import { monitor, monitorScenario, serverMetricThresholds } from '../common/server-monitor.js';

// 스트레스: 부하 테스트의 10배(분당 1,250세션 = 견적 요청 62.5 RPS, VU도 10배)를 5분 동안 보내 무너지는 지점과 원인을 본다
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
            tags: { stage: 'stress' },
        },
        // 서버 지표(Tomcat·Hikari·CPU)를 k6가 2초마다 읽어 summary에 남긴다
        stress_monitor: monitorScenario({ stage: 'stress', duration: '5m' }),
    },
    // 한계를 찾는 테스트라 기준으로 멈추지 않는다. 견적 요청만 따로 보려고 항상 통과하는 기준을 둔다
    thresholds: {
        ...serverMetricThresholds('stress'),
        'http_req_failed{kind:quote}': ['rate>=0'],
        'checks{kind:quote}': ['rate>=0'],
        'http_req_duration{kind:quote}': ['max>=0'],
        'http_req_duration{kind:quote,name:issueQuote}': ['max>=0'],
        'http_req_duration{kind:quote,name:changeQuantity}': ['max>=0'],
        'http_req_duration{kind:quote,name:removeItem}': ['max>=0'],
    },
};

export { monitor, session };
