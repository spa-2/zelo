import { browse } from './journeys/browse.js';
import { quoteChat } from './journeys/quote-chat.js';
import { purchase } from './journeys/purchase.js';
import { myOrders } from './journeys/my-orders.js';
import { COUNT_DISPLAY, GRACEFUL_STOP, rate, SHARE, setupData } from './workload.js';

// 0단계 통합 사전 확인 (계획서 2장): 목표의 0.1배로 2분 동안 네 흐름이 오류 없이 끝까지 가는지 본다
// 특히 B·C가 끝까지 완료되는지(visit{state:completed}, purchase_step{step:completed})를 본다. LLM·포트원은 통합 부하와 같은 fake 설정이다 (integrated-load.js 주석)
// 분당 방문: A 10, B 8, C 5, D 3. 실패가 하나라도 있으면 본 테스트로 넘어가지 않는다
// 실행: k6 run -e BASE_URL=... load-test/integrated/integrated-smoke.js
const MULTIPLIER = Number(__ENV.MULTIPLIER || 0.1);
const DURATION = __ENV.DURATION || '2m';

export const options = {
    scenarios: Object.fromEntries(Object.keys(SHARE).map((name) => [name, {
        executor: 'constant-arrival-rate',
        rate: rate(name, MULTIPLIER),
        timeUnit: '1m',
        duration: DURATION,
        preAllocatedVUs: 5,
        maxVUs: 30,
        gracefulStop: GRACEFUL_STOP,
        exec: name,
        tags: { journey: name },
    }])),
    // 표시용 기준(COUNT_DISPLAY)을 먼저 펼치고, 중단 0건 기준으로 덮어쓴다
    thresholds: {
        ...COUNT_DISPLAY,
        http_req_failed: ['rate==0'],
        checks: ['rate==1'],
        'visit{state:aborted}': ['count==0'],
        // 스크립트 예외로 방문이 중간에 끊기면 실패로도 중단으로도 세지 않으므로, 흐름마다 완료가 1건 이상인지 본다
        ...Object.fromEntries(Object.keys(SHARE).map((name) => [`visit{state:completed,journey:${name}}`, ['count>0']])),
        'purchase_step{step:completed}': ['count>0'],
    },
};

export function setup() {
    return setupData();
}

export { browse, quoteChat, purchase, myOrders };
