import { session, TARGET_SESSIONS_PER_MINUTE } from './chat-session.js';

// 부하: 목표 트래픽(세션 분당 125개 = 메시지 10.4 RPS)에서 기준을 통과하는지 본다
// 데우기 2분(0 → 목표) 뒤 측정 STEADY(기본 10분)를 보내고, 기준은 측정 구간(phase:steady)에만 건다
//
// 실제 OpenAI (AWS 1단계, 계획서 5장). 실행 전에 호출 수와 비용을 알리고 승인을 받는다 (10분이면 약 15,100회)
//   LLM_MODE=real ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.scheduler.enabled=false'
//   k6 run ... load-test/chat/chat-load.js
// fake LLM (로컬). 지연은 실측 범위, 의도는 문장 단어로 고른다. 의도별 성공 조건은 보지 않는다(-e LLM=fake)
//   ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.llm.fake.plan-intent=SHOPPING_REQUEST
//     --zelo.llm.fake.mode=DELAY --zelo.llm.fake.delay=3500ms --zelo.llm.fake.delay-max=6s --zelo.scheduler.enabled=false'
//   k6 run -e LLM=fake ... load-test/chat/chat-load.js
// 결과: mkdir -p docs/load-test/results/chat/dau50k && k6 run --summary-export=docs/load-test/results/chat/dau50k/chat-load-summary.json --out csv=docs/load-test/results/chat/dau50k/chat-load.csv load-test/chat/chat-load.js
// 서버 지표 기준(load-test/chat/record-metrics.sh): hikari_pending 0, 서버 자체 처리 시간 = 채팅 시간 − LLM 시간
const WARMUP = '2m';
const STEADY = __ENV.STEADY || '10m';

// 동시 세션 = 분당 125개 × 48초(fake 기준) ÷ 60 = 100. 서버가 느려져 세션이 길어지는 만큼 여유를 둔다
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
        // 계획서 4-2: 기대와 다른 의도 5% 미만 (fake로 돌리면 이 check가 없다)
        'checks{phase:steady,kind:intent}': ['rate>0.95'],
        // LLM 시간 제한 12초 + 서버 처리 1초. 실제 OpenAI에서 견적 수정은 LLM 3번이라 의도별 값은 결과서에 따로 적는다 (계획서 4-1)
        'http_req_duration{phase:steady,name:startChat}': ['p(95)<13000'],
        'http_req_duration{phase:steady,name:sendMessage}': ['p(95)<13000'],
        'dropped_iterations{phase:steady}': ['count==0'],
    },
};

export { session };
