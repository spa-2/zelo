import { session, TARGET_SESSIONS_PER_MINUTE } from './chat-session.js';

// 스트레스: 목표의 1·1.5·2·2.5·3배(메시지 10.4~31.3 RPS)로 올리며 처음 무너지는 단계와 원인을 찾는다
// 단계마다 동시 세션 = 분당 세션 × 48초 ÷ 60 = 100, 150, 200, 250, 300. 메시지 하나가 LLM을 기다리는 동안 Tomcat 스레드를 잡아서
// ×2(동시 메시지 약 200건)부터 Tomcat 스레드 200개에 닿을 것으로 본다
// fake LLM으로만 돌린다. 실제 OpenAI로 하면 약 47,100회(약 $13.5)라 계획서 5-0에서 뺐다
// 지연은 실측(채팅 도메인 부하·통합 부하)에서 잰 작업별 범위로 맞추고, 의도는 문장 단어로 섞는다 (FakeLlmGateway)
//   ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.llm.fake.plan-intent=SHOPPING_REQUEST
//     --zelo.llm.fake.mode=DELAY --zelo.llm.fake.delay=3500ms --zelo.llm.fake.delay-max=6s --zelo.scheduler.enabled=false
//     --zelo.llm.fake.operation-delays=MENU_PLAN:1500ms-5300ms,QUOTE_EXPLAIN:1300ms-1800ms,QUOTE_EDIT_PLAN:4900ms-7800ms
//     --zelo.llm.fake.intent-keywords=QUOTE_EDIT:빼,LOOKUP_QUESTION:리뷰,GENERAL_QUESTION:보관'
//   (지연 값은 2026-11-05 실제 10건 기준이다. 실측이 나오면 그 값으로 바꾼다. --args 안에서는 띄어쓰기로 인자가 나뉘어 의도 단어에 띄어쓰기를 넣지 않는다)
// mkdir -p docs/load-test/results/chat/dau50k && k6 run -e LLM=fake --summary-export=docs/load-test/results/chat/dau50k/chat-stress-summary.json --out csv=docs/load-test/results/chat/dau50k/chat-stress.csv load-test/chat/chat-stress.js
// 같이 볼 서버 지표(load-test/chat/record-metrics.sh): tomcat_busy, hikari_active, hikari_pending, chat_inflight
const MULTIPLIERS = [1, 1.5, 2, 2.5, 3];

export const options = {
    scenarios: {
        stress: {
            executor: 'ramping-arrival-rate',
            startRate: TARGET_SESSIONS_PER_MINUTE,
            timeUnit: '1m',
            // 단계마다 10초 동안 올리고 80초 유지. 5단계 7분 30초. k6는 정수만 받아서 ×1.5·×2.5는 188·313으로 반올림
            stages: MULTIPLIERS.flatMap((multiplier) => [
                { duration: '10s', target: Math.round(TARGET_SESSIONS_PER_MINUTE * multiplier) },
                { duration: '80s', target: Math.round(TARGET_SESSIONS_PER_MINUTE * multiplier) },
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
