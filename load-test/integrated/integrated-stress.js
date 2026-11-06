import { browse } from './journeys/browse.js';
import { quoteChat } from './journeys/quote-chat.js';
import { purchase } from './journeys/purchase.js';
import { myOrders } from './journeys/my-orders.js';
import { COUNT_DISPLAY, GRACEFUL_STOP, rate, SHARE, setupData, VUS } from './workload.js';

// 3단계 통합 스트레스 (계획서 10장): 네 흐름을 같은 비율로 섞은 채 ×1 → ×3으로 올려 시스템 전체의 첫 병목을 찾는다
// 단계마다 올리기 30초 + 유지 3분 (분석에서는 각 단계의 처음 30초를 뺀다). ×1~×3 5단계 약 17.5분
// ×3에서도 막히지 않으면 -e EXTRA=4로 ×4 단계를 더한다 (fake라 OpenAI 비용이 없다)
// LLM·포트원은 통합 부하와 같은 fake 설정이다 (integrated-load.js 주석, 계획서 5장). 단계별 집계는 CSV의 보낸 시각(timestamp − duration)으로 나눈다 (계획서 7-1)
// 실행: mkdir -p docs/load-test/results/integrated && k6 run -e BASE_URL=... --summary-export=docs/load-test/results/integrated/stress-summary.json
//         --out csv=docs/load-test/results/integrated/stress.csv load-test/integrated/integrated-stress.js
const MULTIPLIERS = [1, 1.5, 2, 2.5, 3, ...(__ENV.EXTRA ? [Number(__ENV.EXTRA)] : [])];
const MAX_MULTIPLIER = Math.max(...MULTIPLIERS);
const RAMP = '30s';
const HOLD = '3m';

function stress(name) {
    return {
        executor: 'ramping-arrival-rate',
        startRate: 0,
        timeUnit: '1m',
        stages: MULTIPLIERS.flatMap((multiplier) => [
            { duration: RAMP, target: rate(name, multiplier) },
            { duration: HOLD, target: rate(name, multiplier) },
        ]),
        preAllocatedVUs: VUS[name][0],
        // 높은 배수에서 서버가 느려지면 방문이 길어져 VU가 더 필요하다. 모자라면 dropped_iterations로 남는다
        maxVUs: Math.ceil(VUS[name][1] * MAX_MULTIPLIER),
        gracefulStop: GRACEFUL_STOP,
        exec: name,
        tags: { journey: name },
    };
}

// 멈춤 조건 (계획서 10장): 실패율 10% 초과 또는 p95가 기준(4-1-1)의 5배를 넘는 상태가 2분 계속되면 멈춘다
function abortWhen(threshold) {
    return [{ threshold, abortOnFail: true, delayAbortEval: '2m' }];
}

export const options = {
    scenarios: Object.fromEntries(Object.keys(SHARE).map((name) => [name, stress(name)])),
    thresholds: {
        http_req_failed: abortWhen('rate<0.1'),
        'http_req_duration{name:autocomplete}': abortWhen('p(95)<1000'),
        'http_req_duration{api:read}': abortWhen('p(95)<2500'),
        'http_req_duration{name:login,loginType:email}': abortWhen('p(95)<2500'),
        'http_req_duration{name:login,loginType:social}': abortWhen('p(95)<10000'),
        'http_req_duration{api:write}': abortWhen('p(95)<5000'),
        'http_req_duration{kind:payment}': abortWhen('p(95)<7500'),
        'http_req_duration{api:ai}': abortWhen('p(95)<100000'),
        ...COUNT_DISPLAY,
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
    return setupData();
}

export { browse, quoteChat, purchase, myOrders };
