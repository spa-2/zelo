import { session, TARGET_SESSIONS_PER_MINUTE } from './quote-session.js';
import { monitor, monitorScenario, serverMetricThresholds } from '../common/server-monitor.js';

// 부하: 목표 트래픽(분당 125세션 = 견적 요청 6.25 RPS)을 5분 동안 보내 기준을 통과하는지 본다
// AWS는 계획서(8-1)대로 10분 보낸다: -e DURATION=10m
// VU = 초당 세션(125 ÷ 60) × 스모크 세션 p95(초), 올림(최소 1). 스모크 결과로 계산해 -e VUS=로 넘긴다
// preAllocatedVUs = VU × 2 (최소 5), maxVUs = VU × 10 (팀 기준)
// 견적 발급 기준 1초는 #244 목표
const VUS = Number(__ENV.VUS || 1);
const DURATION = __ENV.DURATION || '5m';

export const options = {
    scenarios: {
        load: {
            executor: 'constant-arrival-rate',
            rate: TARGET_SESSIONS_PER_MINUTE,
            timeUnit: '1m',
            duration: DURATION,
            preAllocatedVUs: Math.max(VUS * 2, 5),
            maxVUs: VUS * 10,
            exec: 'session',
            tags: { stage: 'load' },
        },
        // 서버 지표(Tomcat·Hikari·CPU)를 k6가 2초마다 읽어 summary에 남긴다
        load_monitor: monitorScenario({ stage: 'load', duration: DURATION }),
    },
    thresholds: {
        ...serverMetricThresholds('load'),
        'http_req_failed{kind:quote}': ['rate<0.01'],
        'checks{kind:quote}': ['rate>0.99'],
        'http_req_duration{kind:quote,name:issueQuote}': ['p(95)<1000'],
        'http_req_duration{kind:quote,name:changeQuantity}': ['p(95)<300'],
        'http_req_duration{kind:quote,name:removeItem}': ['p(95)<300'],
        'http_req_duration{kind:quote}': ['max>=0'],
    },
};

export { monitor, session };
