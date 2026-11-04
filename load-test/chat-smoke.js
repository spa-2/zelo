import { session } from './chat-session.js';

// 스모크: 스크립트, 로그인, 채팅 흐름 전체가 오류 없이 도는지 본다. 세션 하나가 약 48초라 2분 동안 VU 3명이 2~3번씩 돈다
//   JWT_SECRET=... OPENAI_API_KEY=fake ./gradlew bootRun --args='--spring.profiles.active=performance
//     --zelo.llm.fake.plan-intent=SHOPPING_REQUEST --zelo.llm.fake.mode=DELAY --zelo.llm.fake.delay=3500ms --zelo.llm.fake.delay-max=6s
//     --zelo.seed.enabled=true --zelo.seed.load-test.enabled=true --zelo.delivery.scheduler.enabled=false'
// k6 run --summary-export=$HOME/zelo-load/smoke-summary.json --out csv=$HOME/zelo-load/smoke.csv load-test/chat-smoke.js
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
        checks: ['rate==1'],
    },
};

export { session };
