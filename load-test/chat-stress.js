import { session, TARGET_SESSIONS_PER_MINUTE } from './chat-session.js';

// 스트레스: 목표의 1·3·6·9·12·15배(메시지 2.1~31.3 RPS)로 올리며 처음 무너지는 단계와 원인을 찾는다
// 단계마다 동시 세션 = 분당 세션 × 48초 ÷ 60 = 20, 60, 120, 180, 240, 300. ×12부터 Tomcat 스레드 200개를 넘을 것으로 본다
//   JWT_SECRET=... OPENAI_API_KEY=fake ./gradlew bootRun --args='--spring.profiles.active=performance
//     --zelo.llm.fake.plan-intent=SHOPPING_REQUEST --zelo.llm.fake.mode=DELAY --zelo.llm.fake.delay=3500ms --zelo.llm.fake.delay-max=6s
//     --zelo.seed.enabled=true --zelo.seed.load-test.enabled=true --zelo.delivery.scheduler.enabled=false'
// k6 run --summary-export=$HOME/zelo-load/stress-summary.json --out csv=$HOME/zelo-load/stress.csv load-test/chat-stress.js
// 같이 볼 서버 지표(load-test/record-metrics.sh): tomcat_busy, hikari_active, hikari_pending, chat_inflight
const MULTIPLIERS = [1, 3, 6, 9, 12, 15];

export const options = {
    scenarios: {
        stress: {
            executor: 'ramping-arrival-rate',
            startRate: TARGET_SESSIONS_PER_MINUTE,
            timeUnit: '1m',
            // 단계마다 10초 동안 올리고 80초 유지. 6단계 9분
            stages: MULTIPLIERS.flatMap((multiplier) => [
                { duration: '10s', target: TARGET_SESSIONS_PER_MINUTE * multiplier },
                { duration: '80s', target: TARGET_SESSIONS_PER_MINUTE * multiplier },
            ]),
            // 마지막 단계 동시 세션 300. 서버가 느려져 세션이 길어지는 만큼 여유를 둔다
            preAllocatedVUs: 300,
            maxVUs: 800,
            exec: 'session',
            gracefulStop: '1m',
        },
    },
    // 한계를 찾는 테스트라 기준으로 멈추지 않는다. 단계별 결과는 CSV의 시각으로 나눠 본다
};

export { session };
