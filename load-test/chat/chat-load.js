import { session, TARGET_SESSIONS_PER_MINUTE } from './chat-session.js';

// 부하: 목표 트래픽(세션 분당 125개 = 메시지 10.4 RPS)에서 기준을 통과하는지 본다
// 데우기 2분(0 → 목표) 뒤 측정 STEADY(기본 5분, 로컬 새 기준 결과와 같은 길이)를 보내고, 기준은 측정 구간(phase:steady)에만 건다
//
// LLM은 fake다 (계획서 5장). 실제 OpenAI는 이 키의 gpt-6-luna 한도가 분당 토큰 200만이라, 목표(분당 약 320만 토큰)에서 429가 난다
// 지연은 2026-11-06 로컬 실제 OpenAI 부하에서 잰 작업별 시간(zelo_llm_attempt_seconds, 성공한 시도)으로 정했다
//   범위는 [실측 p5, 평균이 실측 평균과 같아지는 값]이다. FakeLlmGateway는 범위 안에서 고르게 고른다 (계획서 5-1)
// 의도는 문장 단어로 고르고, 맞는 단어가 없으면 plan-intent(견적 요청)다. --args 안에서는 띄어쓰기로 인자가 나뉘어 단어에 띄어쓰기를 넣지 않는다
//   ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.llm.fake.mode=DELAY --zelo.llm.fake.plan-intent=SHOPPING_REQUEST
//     --zelo.llm.fake.operation-delays=MENU_PLAN:1100ms-3600ms,QUOTE_EXPLAIN:1000ms-1800ms,QUOTE_EDIT_PLAN:1100ms-2200ms,LOOKUP_ANSWER:2600ms-4100ms,CHAT_ANSWER:1400ms-2300ms
//     --zelo.llm.fake.intent-keywords=QUOTE_EDIT:빼,LOOKUP_QUESTION:리뷰,GENERAL_QUESTION:보관'
//   (LLM_MODE는 기본이 fake라 따로 주지 않는다)
//   AWS에서는 실행 인자를 줄 수 없어 SSM 파라미터(이름 = 프로퍼티 이름)로 넣고 앱을 다시 띄운다 (계획서 6-2)
// 결과: mkdir -p docs/load-test/results/chat/dau50k && k6 run --summary-export=docs/load-test/results/chat/dau50k/chat-load-summary.json --out csv=docs/load-test/results/chat/dau50k/chat-load.csv load-test/chat/chat-load.js
// 서버 지표 기준(load-test/chat/record-metrics.sh): hikari_pending 0, 서버 자체 처리 시간 = 채팅 시간 − LLM 시간
const WARMUP = '2m';
const STEADY = __ENV.STEADY || '5m';

// 동시 세션 = 분당 125개 × 세션 시간 ÷ 60. 위 fake 지연이면 세션이 약 25초(2026-11-06 스모크)라 약 52개다.
// 서버가 느려져 세션이 길어지는 만큼 여유를 둔다
const VUS = { preAllocatedVUs: 150, maxVUs: 400 };

export const options = {
    scenarios: {
        warmup: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1m',
            stages: [{ duration: WARMUP, target: TARGET_SESSIONS_PER_MINUTE }],
            ...VUS,
            exec: 'session',
            gracefulStop: '1m',
            tags: { phase: 'warmup' },
        },
        steady: {
            executor: 'constant-arrival-rate',
            rate: TARGET_SESSIONS_PER_MINUTE,
            timeUnit: '1m',
            startTime: WARMUP,
            duration: STEADY,
            ...VUS,
            exec: 'session',
            gracefulStop: '1m',
            tags: { phase: 'steady' },
        },
    },
    thresholds: {
        'http_req_failed{phase:steady}': ['rate<0.01'],
        'checks{phase:steady,kind:basic}': ['rate>0.99'],
        // 계획서 4-2-1: 응답 형식(견적 카드·글 답변)이 기대와 다른 메시지 1% 미만
        'checks{phase:steady,kind:format}': ['rate>0.99'],
        // LLM 시간 제한 12초 + 서버 처리 1초. 의도별 값은 결과서에 따로 적는다 (계획서 4-2-1)
        'http_req_duration{phase:steady,name:startChat}': ['p(95)<13000'],
        'http_req_duration{phase:steady,name:sendMessage}': ['p(95)<13000'],
        'dropped_iterations{phase:steady}': ['count==0'],
    },
};

export { session };
