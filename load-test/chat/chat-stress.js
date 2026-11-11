import { session, TARGET_SESSIONS_PER_MINUTE } from './chat-session.js';

// 스트레스: 목표의 1·1.5·2·2.5·3배(메시지 10.4~31.3 RPS)로 올리며 처음 무너지는 단계와 원인을 찾는다
// 단계마다 30초 동안 올리고 3분 유지한다 (계획서 8-1: 단계마다 3분 이상. 로컬에서 쓴 80초는 단계 사이에 섞이는 구간이 컸다). 5단계 약 17.5분
// 분석에서는 각 단계의 처음 30초를 빼고, 단계는 CSV의 보낸 시각(timestamp − duration)으로 나눈다 (계획서 7-1)
// LLM은 chat-load.js와 같은 fake 설정으로 띄운다 (실측 지연, 문장별 의도)
// mkdir -p docs/load-test/results/chat/dau50k && k6 run --summary-export=docs/load-test/results/chat/dau50k/chat-stress-summary.json --out csv=docs/load-test/results/chat/dau50k/chat-stress.csv load-test/chat/chat-stress.js
// 같이 볼 서버 지표(load-test/chat/record-metrics.sh): tomcat_busy, hikari_active, hikari_pending, chat_inflight
const MULTIPLIERS = [1, 1.5, 2, 2.5, 3];

export const options = {
    scenarios: {
        stress: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1m',
            // k6는 정수만 받아서 ×1.5·×2.5는 188·313으로 반올림
            stages: MULTIPLIERS.flatMap((multiplier) => [
                { duration: '30s', target: Math.round(TARGET_SESSIONS_PER_MINUTE * multiplier) },
                { duration: '3m', target: Math.round(TARGET_SESSIONS_PER_MINUTE * multiplier) },
            ]),
            // 마지막 단계 동시 세션은 분당 375개 × 세션 약 25초 ÷ 60 = 약 156개. 서버가 느려져 세션이 길어지는 만큼 여유를 둔다
            preAllocatedVUs: 300,
            maxVUs: 800,
            exec: 'session',
            gracefulStop: '1m',
        },
    },
    // 한계를 찾는 테스트라 기준으로 멈추지 않는다. 단계별 결과는 CSV의 시각으로 나눠 본다
};

export { session };
