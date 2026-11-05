import { session } from './chat-session.js';

// 스모크: 스크립트, 로그인, 채팅 흐름 전체가 오류 없이 도는지 본다. 세션 하나가 약 48초(fake)라 2분 동안 VU 3명이 2~3번씩 돈다
// 실제 OpenAI로 돌리면(사전 확인) 5개 문장이 모두 기대한 의도로 처리되는지도 본다. 실제 메시지는 4초 안팎이라 2분에 세션 약 14개, 호출 약 150회다. 실행 전에 승인을 받는다
//   실제: LLM_MODE=real ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.scheduler.enabled=false'
//         k6 run ... load-test/chat/chat-smoke.js
//   fake: ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.llm.fake.plan-intent=SHOPPING_REQUEST
//           --zelo.llm.fake.mode=DELAY --zelo.llm.fake.delay=3500ms --zelo.llm.fake.delay-max=6s --zelo.scheduler.enabled=false'
//         k6 run -e LLM=fake ... load-test/chat/chat-smoke.js
// 결과: mkdir -p docs/load-test/results/chat/dau50k && k6 run --summary-export=docs/load-test/results/chat/dau50k/chat-smoke-summary.json --out csv=docs/load-test/results/chat/dau50k/chat-smoke.csv load-test/chat/chat-smoke.js
export const options = {
    scenarios: {
        smoke: {
            executor: 'constant-vus',
            vus: 3,
            duration: '2m',
            exec: 'session',
            gracefulStop: '1m',
        },
    },
    // 하나라도 실패하면 본 테스트로 넘어가지 않는다
    thresholds: {
        http_req_failed: ['rate==0'],
        'checks{kind:basic}': ['rate==1'],
        // 실제 OpenAI일 때만 있는 check. 하나라도 다르면 문장이나 프롬프트를 먼저 본다
        'checks{kind:intent}': ['rate==1'],
    },
};

export { session };
