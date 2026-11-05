import { session, TARGET_SESSIONS_PER_MINUTE } from './quote-session.js';
import { monitor, monitorScenario, serverMetricThresholds } from '../common/server-monitor.js';

// 한계: 목표(분당 125세션)의 1·2·4·6·8·10배(분당 125~1,250세션)로 올리며 처음 무너지는 단계와 원인을 찾는다
// 단계마다 60초. 단계를 시나리오로 나누고 stage 태그를 붙여 summary에 단계별 결과가 남게 한다. 6단계 6분
// 서버 지표(Tomcat·Hikari·CPU)도 k6가 2초마다 읽어 같은 stage 태그로 남긴다
// VU는 초당 세션 × 2초만큼 미리 만들고 그 3배까지 늘린다. 그래도 모자라 못 보낸 세션은 dropped_iterations로 남는다
// 단계를 바꾸려면 -e MULTIPLIERS=20,30,40 처럼 넘긴다
const MULTIPLIERS = (__ENV.MULTIPLIERS || '1,2,4,6,8,10').split(',').map(Number);
const STAGE_SECONDS = Number(__ENV.STAGE_SECONDS || 60);

const scenarios = {};
const thresholds = {};
MULTIPLIERS.forEach((multiplier, index) => {
    const stage = `x${multiplier}`;
    const perSecond = TARGET_SESSIONS_PER_MINUTE * multiplier / 60;
    const startTime = `${STAGE_SECONDS * index}s`;
    const duration = `${STAGE_SECONDS}s`;
    scenarios[stage] = {
        executor: 'constant-arrival-rate',
        rate: TARGET_SESSIONS_PER_MINUTE * multiplier,
        timeUnit: '1m',
        duration,
        startTime,
        preAllocatedVUs: Math.ceil(perSecond * 2),
        maxVUs: Math.ceil(perSecond * 6),
        exec: 'session',
        gracefulStop: '30s',
        tags: { stage },
    };
    scenarios[`${stage}_monitor`] = monitorScenario({ stage, startTime, duration });
    // 한계를 찾는 테스트라 기준으로 멈추지 않는다. 단계별 값을 summary에 남기려고 항상 통과하는 기준을 둔다
    thresholds[`http_reqs{kind:quote,name:issueQuote,stage:${stage}}`] = ['count>=0'];
    thresholds[`http_req_failed{kind:quote,stage:${stage}}`] = ['rate>=0'];
    thresholds[`checks{kind:quote,stage:${stage}}`] = ['rate>=0'];
    thresholds[`http_req_duration{kind:quote,name:issueQuote,stage:${stage}}`] = ['max>=0'];
    thresholds[`http_req_duration{kind:quote,name:changeQuantity,stage:${stage}}`] = ['max>=0'];
    thresholds[`dropped_iterations{scenario:${stage}}`] = ['count>=0'];
    Object.assign(thresholds, serverMetricThresholds(stage));
});

export const options = { scenarios, thresholds };

export { monitor, session };
