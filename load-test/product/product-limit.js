import { browse, setup as prepare, TARGET_PER_10S } from './product-session.js';

// 한계: 목표(4.2 RPS)의 10·25·50·100·150·200·300배(42~1,260 RPS)로 올리며 처음 무너지는 단계와 원인을 찾는다
// 단계마다 90초. 단계를 시나리오로 나눠서 summary에 단계별 결과(scenario 태그)가 남게 한다. 7단계 10분 30초
// VU는 단계 RPS만큼 미리 만들고(응답 1초까지), 그 2배까지 늘린다. 그래도 모자라 못 보낸 요청은 dropped_iterations로 남는다
const MULTIPLIERS = [10, 25, 50, 100, 150, 200, 300];
const STAGE_SECONDS = 90;

const scenarios = {};
const thresholds = {};
MULTIPLIERS.forEach((multiplier, index) => {
    const name = `x${multiplier}`;
    const rps = TARGET_PER_10S * multiplier / 10;
    scenarios[name] = {
        executor: 'constant-arrival-rate',
        rate: TARGET_PER_10S * multiplier,
        timeUnit: '10s',
        duration: `${STAGE_SECONDS}s`,
        startTime: `${STAGE_SECONDS * index}s`,
        preAllocatedVUs: Math.ceil(rps),
        maxVUs: Math.ceil(rps * 2),
        exec: 'browse',
        gracefulStop: '10s',
    };
    // 한계를 찾는 테스트라 기준으로 멈추지 않는다. 단계별 값을 summary에 남기려고 항상 통과하는 기준을 둔다
    thresholds[`http_reqs{kind:product,scenario:${name}}`] = ['count>=0'];
    thresholds[`http_req_failed{kind:product,scenario:${name}}`] = ['rate>=0'];
    thresholds[`http_req_duration{kind:product,scenario:${name}}`] = ['max>=0'];
    thresholds[`http_req_duration{name:search,scenario:${name}}`] = ['max>=0'];
    thresholds[`http_req_duration{name:detail,scenario:${name}}`] = ['max>=0'];
    thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
});

export const options = { scenarios, thresholds };

// 서버 지표 CSV를 단계별로 나누려고 첫 단계가 시작하는 시각을 남긴다
export function setup() {
    const data = prepare();
    console.log(`첫 단계 시작 ${new Date().toISOString()}`);
    return data;
}

export { browse };
