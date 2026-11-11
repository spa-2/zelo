import { browse } from './journeys/browse.js';
import { quoteChat } from './journeys/quote-chat.js';
import { purchase } from './journeys/purchase.js';
import { myOrders } from './journeys/my-orders.js';
import { COUNT_DISPLAY, GRACEFUL_STOP, rate, SHARE, setupData, USER_COUNTS, VUS } from './workload.js';

// 2단계 통합 부하 (계획서 9장): 네 흐름을 비율대로 섞어 피크 목표(×1)에서 기준(4-1)을 지키는지 본다
// 데우기 5분(판정 제외) + 측정 20분. 기준은 측정 구간(phase:steady)에만 건다
// LLM과 포트원은 fake다 (계획서 5장, 6-2). LLM 지연은 실측 값이고 채팅 도메인 테스트(chat-load.js)와 같은 설정이다. 포트원 지연은 performance 프로필에 있다
//   로컬: ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.llm.fake.mode=DELAY --zelo.llm.fake.plan-intent=SHOPPING_REQUEST
//     --zelo.llm.fake.operation-delays=MENU_PLAN:1100ms-3600ms,QUOTE_EXPLAIN:1000ms-1800ms,QUOTE_EDIT_PLAN:1100ms-2200ms,LOOKUP_ANSWER:2600ms-4100ms,CHAT_ANSWER:1400ms-2300ms
//     --zelo.llm.fake.intent-keywords=QUOTE_EDIT:빼,LOOKUP_QUESTION:리뷰,GENERAL_QUESTION:보관
//     --zelo.seed.load-test.payment-order-count=0 --zelo.seed.load-test.extra-stock=100000'
//   payment-order-count=0: 결제 시드가 결제 대기 주문 없이 결제수단만 넣는다. 결제 대기 주문이 있으면 C의 주문 생성이 409(ORDER_004)다
//   extra-stock=100000: 김치·밥·계란·대파 상품 재고를 더한다. fake 메뉴 계획이 늘 같은 재료라 C의 구매가 몰려 재고가 금방 바닥난다 (계획서 3-5)
//   AWS: 같은 값을 SSM 파라미터로 넣고 앱을 다시 띄운다. 통합이 끝나면 payment-order-count와 extra-stock은 지운다 (계획서 6-2)
// 실행: mkdir -p docs/load-test/results/integrated && k6 run -e BASE_URL=... --summary-export=docs/load-test/results/integrated/load-summary.json
//         --out csv=docs/load-test/results/integrated/load.csv load-test/integrated/integrated-load.js
//   같이 돌릴 것: 서버 지표 수집(load-test/chat/record-metrics.sh, METRICS_URL=앱 9292 주소), 시작·끝 Prometheus 전체 저장(계획서 7-1)
const MULTIPLIER = Number(__ENV.MULTIPLIER || 1);
// 구매(C) 방문 완료율 기준 (계획서 4-1-2). 사전 확인(integrated-smoke.js) 결과를 보고 -e PURCHASE_COMPLETION_RATE로 바꾼다
const PURCHASE_COMPLETION_RATE = Number(__ENV.PURCHASE_COMPLETION_RATE || 0.99);
const WARMUP = __ENV.WARMUP || '5m';
const STEADY = __ENV.STEADY || '20m';
// 데우기는 3분 동안 절반까지, 2분 동안 목표까지 올린다 (WARMUP을 바꾸면 같은 비율로 나눈다)
const WARMUP_SECONDS = toSeconds(WARMUP);

function warmup(name) {
    return {
        executor: 'ramping-arrival-rate',
        startRate: 0,
        timeUnit: '1m',
        stages: [
            { duration: `${Math.round(WARMUP_SECONDS * 0.6)}s`, target: rate(name, MULTIPLIER * 0.5) },
            { duration: `${Math.round(WARMUP_SECONDS * 0.4)}s`, target: rate(name, MULTIPLIER) },
        ],
        preAllocatedVUs: Math.ceil(VUS[name][0] * MULTIPLIER),
        maxVUs: Math.ceil(VUS[name][1] * MULTIPLIER),
        // 데우기가 끝날 때 진행 중인 방문은 끊지 않고 마저 끝낸다 (끝난 요청은 phase:warmup으로 남는다)
        gracefulStop: GRACEFUL_STOP,
        exec: name,
        tags: { journey: name, phase: 'warmup' },
        env: { USER_OFFSET: '0' },
    };
}

function steady(name) {
    return {
        executor: 'constant-arrival-rate',
        rate: rate(name, MULTIPLIER),
        timeUnit: '1m',
        startTime: WARMUP,
        duration: STEADY,
        preAllocatedVUs: Math.ceil(VUS[name][0] * MULTIPLIER),
        maxVUs: Math.ceil(VUS[name][1] * MULTIPLIER),
        gracefulStop: GRACEFUL_STOP,
        exec: name,
        tags: { journey: name, phase: 'steady' },
        // 데우기가 앞쪽 사용자를 썼으므로 측정은 번호대 중간부터 쓴다 (같은 주문 두 번 환불, 메시지 제한 방지)
        env: { USER_OFFSET: String(Math.floor((USER_COUNTS[name] || 0) / 2)) },
    };
}

export const options = {
    scenarios: Object.fromEntries(Object.keys(SHARE).flatMap((name) => [
        [`${name}Warmup`, warmup(name)],
        [`${name}Steady`, steady(name)],
    ])),
    // 계획서 4-1 통합 기준. 측정 구간만 판정한다
    thresholds: {
        'http_req_failed{phase:steady}': ['rate<0.01'],
        'http_req_failed{phase:steady,kind:payment}': ['rate<0.001'],
        // 결제 API는 실패도 200으로 돌려줘서 응답의 결제 상태로 따로 센다 (journeys/purchase.js)
        'payment_failed{phase:steady}': ['rate<0.001'],
        // 4xx·5xx는 http_req_failed가 잡고, 기대와 다른 2xx(예: 201 대신 200, 토큰 없는 로그인 200)는 상태 코드 check가 잡는다
        'checks{phase:steady,kind:status}': ['rate>0.99'],
        // HTTP는 성공했지만 업무 상태가 틀려 끊긴 구매(결제 FAILED, 결제 뒤 주문이 PAID 아님, 결제수단 없음)까지 잡는다
        'visit_completed{phase:steady,journey:purchase}': [`rate>=${PURCHASE_COMPLETION_RATE}`],
        'http_req_duration{phase:steady,name:autocomplete}': ['p(95)<200'],
        'http_req_duration{phase:steady,api:read}': ['p(95)<500'],
        'http_req_duration{phase:steady,name:login,loginType:email}': ['p(95)<500'],
        'http_req_duration{phase:steady,name:login,loginType:social}': ['p(95)<2000'],
        'http_req_duration{phase:steady,api:write}': ['p(95)<1000'],
        'http_req_duration{phase:steady,kind:payment}': ['p(95)<1500'],
        'http_req_duration{phase:steady,api:ai}': ['p(95)<20000'],
        'checks{phase:steady,kind:format}': ['rate>0.99'],
        'dropped_iterations{phase:steady}': ['count==0'],
        ...COUNT_DISPLAY,
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
    return setupData();
}

export { browse, quoteChat, purchase, myOrders };

function toSeconds(duration) {
    const match = /^(\d+)(s|m)$/.exec(duration);
    if (!match) {
        throw new Error(`WARMUP은 30s, 5m 같은 형식이다 WARMUP=${duration}`);
    }
    return Number(match[1]) * (match[2] === 'm' ? 60 : 1);
}
