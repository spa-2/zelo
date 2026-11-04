import { session, TARGET_SESSIONS_PER_MINUTE } from './chat-session.js';

// 부하: 목표 트래픽(세션 분당 25개 = 메시지 2.1 RPS)을 5분 동안 보내 기준을 통과하는지 본다
//   JWT_SECRET=... OPENAI_API_KEY=fake ./gradlew bootRun --args='--spring.profiles.active=performance
//     --zelo.llm.fake.plan-intent=SHOPPING_REQUEST --zelo.llm.fake.mode=DELAY --zelo.llm.fake.delay=3500ms --zelo.llm.fake.delay-max=6s
//     --zelo.seed.enabled=true --zelo.seed.load-test.enabled=true --zelo.delivery.scheduler.enabled=false'
// k6 run --summary-export=$HOME/zelo-load/load-summary.json --out csv=$HOME/zelo-load/load.csv load-test/chat-load.js
// 서버 지표 기준(load-test/record-metrics.sh): hikari_pending 0, 서버 자체 처리 시간 = 채팅 시간 − LLM 시간
export const options = {
    scenarios: {
        load: {
            executor: 'constant-arrival-rate',
            rate: TARGET_SESSIONS_PER_MINUTE,
            timeUnit: '1m',
            duration: '5m',
            // 동시 세션 = 분당 25개 × 48초 ÷ 60 = 20
            preAllocatedVUs: 30,
            maxVUs: 100,
            exec: 'session',
            gracefulStop: '1m',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        checks: ['rate>0.99'],
        // LLM 시간 제한 12초 + 서버 처리 1초
        'http_req_duration{name:startChat}': ['p(95)<13000'],
        'http_req_duration{name:sendMessage}': ['p(95)<13000'],
    },
};

export { session };
