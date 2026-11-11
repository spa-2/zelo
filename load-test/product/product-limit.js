import { browse, setup, TARGET_PER_10S } from './product-session.js';
import { monitor, monitorScenario, serverMetricThresholds } from '../common/server-monitor.js';

// 한계: 목표(41.7 RPS)의 1·3·5·10·15·20·30배(42~1,251 RPS)로 올리며 처음 무너지는 단계와 원인을 찾는다
// 단계마다 90초. 단계를 시나리오로 나누고 stage 태그를 붙여 summary에 단계별 결과가 남게 한다. 7단계 10분 30초
// 서버 지표(Tomcat·Hikari·CPU)도 k6가 2초마다 읽어 같은 stage 태그로 남긴다. 따로 기록 스크립트를 켤 필요 없음
// VU는 단계 RPS만큼 미리 만들고(응답 1초까지), 그 2배까지 늘린다. 그래도 모자라 못 보낸 요청은 dropped_iterations로 남는다
// 단계를 바꾸려면 -e MULTIPLIERS=100,120,140 처럼 넘긴다
const MULTIPLIERS = (__ENV.MULTIPLIERS || '1,3,5,10,15,20,30').split(',').map(Number);
const STAGE_SECONDS = Number(__ENV.STAGE_SECONDS || 90);

const scenarios = {};
const thresholds = {};
MULTIPLIERS.forEach((multiplier, index) => {
    const stage = `x${multiplier}`;
    const rps = TARGET_PER_10S * multiplier / 10;
    const startTime = `${STAGE_SECONDS * index}s`;
    const duration = `${STAGE_SECONDS}s`;
    scenarios[stage] = {
        executor: 'constant-arrival-rate',
        rate: TARGET_PER_10S * multiplier,
        timeUnit: '10s',
        duration,
        startTime,
        preAllocatedVUs: Math.ceil(rps),
        maxVUs: Math.ceil(rps * 2),
        exec: 'browse',
        gracefulStop: '10s',
        tags: { stage },
    };
    scenarios[`${stage}_monitor`] = monitorScenario({ stage, startTime, duration });
    // 한계를 찾는 테스트라 기준으로 멈추지 않는다. 단계별 값을 summary에 남기려고 항상 통과하는 기준을 둔다
    thresholds[`http_reqs{kind:product,stage:${stage}}`] = ['count>=0'];
    thresholds[`http_req_failed{kind:product,stage:${stage}}`] = ['rate>=0'];
    thresholds[`http_req_duration{kind:product,stage:${stage}}`] = ['max>=0'];
    thresholds[`http_req_duration{kind:product,name:search,stage:${stage}}`] = ['max>=0'];
    thresholds[`http_req_duration{kind:product,name:detail,stage:${stage}}`] = ['max>=0'];
    thresholds[`dropped_iterations{scenario:${stage}}`] = ['count>=0'];
    Object.assign(thresholds, serverMetricThresholds(stage));
});

export const options = { scenarios, thresholds };

export { browse, monitor, setup };
