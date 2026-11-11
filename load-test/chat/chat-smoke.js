import { session } from './chat-session.js';

// 스모크: 스크립트, 로그인, 채팅 흐름 전체가 오류 없이 도는지 본다. 세션 SESSIONS개(기본 3)를 VU 3명이 나눠 돈다
// 앱은 chat-load.js 주석의 fake 설정으로 띄운다 (계획서 5장)
//   k6 run load-test/chat/chat-smoke.js
// 결과: mkdir -p docs/load-test/results/chat/dau50k && k6 run --summary-export=docs/load-test/results/chat/dau50k/chat-smoke-summary.json --out csv=docs/load-test/results/chat/dau50k/chat-smoke.csv load-test/chat/chat-smoke.js
const SESSIONS = Number(__ENV.SESSIONS || 3);

export const options = {
    scenarios: {
        smoke: {
            executor: 'shared-iterations',
            vus: Math.min(3, SESSIONS),
            iterations: SESSIONS,
            maxDuration: '5m',
            exec: 'session',
        },
    },
    // 하나라도 실패하면 본 테스트로 넘어가지 않는다
    thresholds: {
        http_req_failed: ['rate==0'],
        'checks{kind:basic}': ['rate==1'],
        'checks{kind:format}': ['rate==1'],
    },
};

export { session };
