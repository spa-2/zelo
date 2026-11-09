import exec from 'k6/execution';
import { browse } from './journeys/browse.js';
import { quoteChat } from './journeys/quote-chat.js';
import { purchase } from './journeys/purchase.js';
import { myOrders } from './journeys/my-orders.js';
import { useRequestTags } from './common.js';
import { COUNT_DISPLAY, GRACEFUL_STOP, rate, SHARE, setupData, VUS } from './workload.js';

// 3단계 통합 스트레스 (계획서 10장): 네 흐름을 같은 비율로 섞은 채 ×1 → ×3으로 올려 시스템 전체의 첫 병목을 찾는다
// 단계마다 올리기 30초 + 유지 3분. ×1~×3 5단계 약 17.5분
// ×3에서도 막히지 않으면 -e EXTRA=4로 ×4 단계를 더한다 (fake라 OpenAI 비용이 없다)
// LLM·포트원은 통합 부하와 같은 fake 설정이다 (integrated-load.js 주석, 계획서 5장)
// 요청마다 보낸 시각으로 stage 태그를 붙인다: 올리기 30초는 ramp, 유지 3분은 x1·x1_5·…, 마지막 단계 뒤(gracefulStop)는 end
//   그래서 요약에 단계별 값이 바로 나온다. CSV에서도 extra_tags의 stage로 나눈다 (보낸 시각 기준, 계획서 7-1)
// 실행: mkdir -p docs/load-test/results/integrated && k6 run -e BASE_URL=... --summary-export=docs/load-test/results/integrated/stress-summary.json
//         --out csv=docs/load-test/results/integrated/stress.csv load-test/integrated/integrated-stress.js
const MULTIPLIERS = [1, 1.5, 2, 2.5, 3, ...(__ENV.EXTRA ? [Number(__ENV.EXTRA)] : [])];
const MAX_MULTIPLIER = Math.max(...MULTIPLIERS);
const RAMP_SECONDS = 30;
const HOLD_SECONDS = 180;

function stress(name) {
    return {
        executor: 'ramping-arrival-rate',
        startRate: 0,
        timeUnit: '1m',
        stages: MULTIPLIERS.flatMap((multiplier) => [
            { duration: `${RAMP_SECONDS}s`, target: rate(name, multiplier) },
            { duration: `${HOLD_SECONDS}s`, target: rate(name, multiplier) },
        ]),
        preAllocatedVUs: VUS[name][0],
        // 높은 배수에서 서버가 느려지면 방문이 길어져 VU가 더 필요하다. 모자라면 dropped_iterations로 남는다
        maxVUs: Math.ceil(VUS[name][1] * MAX_MULTIPLIER),
        gracefulStop: GRACEFUL_STOP,
        exec: name,
        tags: { journey: name },
    };
}

// 태그 값에 점을 쓰지 않으려고 1.5 → x1_5로 쓴다
function stageName(multiplier) {
    return `x${String(multiplier).replace('.', '_')}`;
}

// 네 시나리오가 같이 시작하므로 시나리오 시작 시각에서 지난 시간으로 단계를 정한다
function stageTag() {
    const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
    const index = Math.floor(elapsed / (RAMP_SECONDS + HOLD_SECONDS));
    if (index >= MULTIPLIERS.length) {
        return { stage: 'end' };
    }
    const inStage = elapsed - index * (RAMP_SECONDS + HOLD_SECONDS);
    return { stage: inStage < RAMP_SECONDS ? 'ramp' : stageName(MULTIPLIERS[index]) };
}

useRequestTags(stageTag);

// 멈춤 조건 (계획서 10장): 한 단계의 유지 구간 요청만 모아 실패율 10% 초과 또는 p95가 기준(4-1-1)의 5배를 넘으면 바로 멈춘다
// 테스트 전체 누적으로 보면 앞 단계의 정상 요청에 묻혀 늦게 멈춰서, 단계(stage 태그)마다 따로 판정한다
// k6에는 "기준을 넘는 상태가 N분 계속"을 거는 기능이 없다 (delayAbortEval은 테스트 시작부터 판정을 미루는 시간일 뿐이다)
const ABORT_WHEN = {
    http_req_failed: 'rate<0.1',
    'http_req_duration{name:autocomplete}': 'p(95)<1000',
    'http_req_duration{api:read}': 'p(95)<2500',
    'http_req_duration{name:login,loginType:email}': 'p(95)<2500',
    'http_req_duration{name:login,loginType:social}': 'p(95)<10000',
    'http_req_duration{api:write}': 'p(95)<5000',
    'http_req_duration{kind:payment}': 'p(95)<7500',
};
// 채팅(api:ai)은 기준 20초의 5배(100초)가 요청 timeout 40초(common.js)보다 길어 p95로는 멈출 수 없다
// 서버 시간 예산 30초를 넘으면 504(AI_002), 40초를 넘으면 timeout이라 둘 다 실패율 조건이 잡는다. p95는 단계별로 보이게만 한다
const DISPLAY = {
    'http_req_duration{api:ai}': 'p(95)>=0',
};

function withStage(metric, multiplier) {
    const stage = `stage:${stageName(multiplier)}`;
    return metric.includes('{') ? metric.replace('}', `,${stage}}`) : `${metric}{${stage}}`;
}

function perStage(criteria, toThreshold) {
    return Object.fromEntries(MULTIPLIERS.flatMap((multiplier) => Object.entries(criteria)
        .map(([metric, threshold]) => [withStage(metric, multiplier), toThreshold(threshold)])));
}

export const options = {
    scenarios: Object.fromEntries(Object.keys(SHARE).map((name) => [name, stress(name)])),
    thresholds: {
        ...perStage(ABORT_WHEN, (threshold) => [{ threshold, abortOnFail: true }]),
        ...perStage(DISPLAY, (threshold) => [threshold]),
        ...COUNT_DISPLAY,
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
    return setupData();
}

export { browse, quoteChat, purchase, myOrders };
