import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { loginUsers } from './users.js';

// 장애 테스트: OpenAI가 답하지 않는 장애에서 서킷 브레이커가 열려 대기가 끊기는지, 장애가 끝나면 스스로 닫히는지 본다
// LLM 호출을 시간 제한(12초)보다 긴 15초로 늦춰서 띄운다. 서킷이 닫혀 있으면 메시지 하나가 최대 약 24.5초(12초 × 2 + 대기 0.5초) 뒤 FALLBACK이다.
// 첫 5건이 12초쯤 시간 초과되어 실패율 100%로 서킷이 열리고, 그 뒤로는 LLM을 부르지 않고 바로 FALLBACK이다.
// 30초 뒤 반쯤 열려 시험 3번을 보내고, 그 3번도 12초 뒤 실패해 다시 열린다
// RECOVER_AFTER초에 fake LLM을 정상 응답 시간(3.5~6초)으로 바꾼다(FakeLlmController). 다음 반쯤 열림에서 시험 3번이 성공하면 서킷이 닫히고 견적 카드가 다시 온다
//   ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.llm.fake.plan-intent=SHOPPING_REQUEST
//     --zelo.llm.fake.mode=DELAY --zelo.llm.fake.delay=15s --zelo.seed.enabled=true --zelo.seed.load-test.enabled=true'
// mkdir -p docs/load-test/results/chat && k6 run --summary-export=docs/load-test/results/chat/chat-outage-summary.json --out csv=docs/load-test/results/chat/chat-outage.csv load-test/chat/chat-llm-outage.js
// 같이 볼 서버 지표(load-test/chat/record-metrics.sh): circuit, not_permitted, fallback_timeout, fallback_circuit, tomcat_busy
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// 목표(2.1 RPS)의 약 5배. 서킷이 없다면 초당 10건 × 24.5초 = 동시 245건으로 Tomcat 스레드 200개를 넘는다
const RATE = Number(__ENV.RATE || 10);
// 사용자 1명이 분당 10 × 60 ÷ 500 = 1.2건이라 메시지 제한(1분 10건)에 걸리지 않는다
const USERS = Number(__ENV.USERS || 500);
const RECOVER_AFTER = Number(__ENV.RECOVER_AFTER || 120);

export const options = {
    // 사용자를 하나씩 로그인시켜서(BCrypt) 기본 60초로는 모자랄 수 있다
    setupTimeout: '5m',
    scenarios: {
        outage: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: __ENV.DURATION || '4m30s',
            // 서킷이 닫혀 있는 동안은 요청 하나가 최대 24.5초라 동시 245건까지 늘 수 있다
            preAllocatedVUs: 100,
            maxVUs: 300,
            exec: 'chat',
        },
        // 장애가 끝난 시점을 정해 두려고 한 번만 보낸다
        recover: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: 1,
            startTime: `${RECOVER_AFTER}s`,
            exec: 'recover',
        },
    },
    // FALLBACK은 오류 코드의 5xx로 오고, 복구 뒤에는 201로 온다. 그 밖의 상태만 실패로 센다
    thresholds: {
        'http_req_failed{name:startChat}': ['rate<0.01'],
        'checks{check:FALLBACK 또는 견적 카드}': ['rate>0.99'],
        'checks{check:복구 전환}': ['rate==1'],
    },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function setup() {
    return { tokens: loginUsers(BASE_URL, USERS), startedAt: Date.now() };
}

export function chat(data) {
    const token = data.tokens[exec.scenario.iterationInTest % data.tokens.length];
    // 결과 CSV에서 장애 구간과 복구 구간을 나눠 보려고 전환 시각 기준으로 태그를 붙인다
    const phase = Date.now() - data.startedAt < RECOVER_AFTER * 1000 ? 'outage' : 'recovery';
    const res = http.post(`${BASE_URL}/api/chat/messages`, JSON.stringify({ text: '김치볶음밥 재료 준비해 줘' }), {
        headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() },
        tags: { name: 'startChat', phase },
        responseCallback: http.expectedStatuses(201, 502, 503, 504),
    });
    check(res, {
        'FALLBACK 또는 견적 카드': (r) => ['FALLBACK', 'QUOTE_CARD'].includes(r.json('data.message.payload.type')),
    });
}

export function recover(data) {
    const res = http.post(`${BASE_URL}/api/fake/llm/mode`,
        JSON.stringify({ mode: 'DELAY', delayMillis: 3500, delayMaxMillis: 6000 }), {
            headers: { ...JSON_HEADERS, Authorization: `Bearer ${data.tokens[0]}` },
            tags: { name: 'recover' },
        });
    check(res, { '복구 전환': (r) => r.status === 200 });
}
