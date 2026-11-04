import http from 'k6/http';
import { check } from 'k6';

// 견적 발급 부하 테스트 (#244)
// 앱은 LLM fake 모드로 띄워서 OpenAI 대기 시간을 빼고 서버 쪽 처리 시간만 잼
// fake 기본값은 일반 질문이라 견적이 안 나와서 아래 옵션으로 띄워야 함
//   --zelo.llm.mode=fake --zelo.llm.fake.plan-intent=SHOPPING_REQUEST
// k6 run -e BASE_URL=http://localhost:8080 -e VUS=50 load-test/quote-issue.js
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 10);

export const options = {
    // 사용자를 하나씩 가입·로그인시켜서 VUS가 많으면 기본 60초로는 모자람
    setupTimeout: '5m',
    scenarios: {
        quote: {
            executor: 'constant-vus',
            vus: VUS,
            duration: __ENV.DURATION || '1m',
        },
    },
    // setup의 가입·로그인은 빼고 견적 발급 요청만 봄
    thresholds: {
        'http_req_failed{name:startChat}': ['rate<0.01'],
        'http_req_duration{name:startChat}': ['p(95)<1000'],
        // 201인데 카드가 없는 경우가 다른 검사에 묻히지 않게 따로 봄
        'checks{check:견적 카드}': ['rate>0.99'],
    },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// 사용자마다 토큰 하나씩 만들어 두고 VU가 자기 토큰만 씀
export function setup() {
    const runId = Date.now();
    const tokens = [];
    for (let i = 0; i < VUS; i++) {
        const email = `load${runId}-${i}@test.com`;
        const password = 'Zelo1234!';
        const signUp = http.post(`${BASE_URL}/api/users`, JSON.stringify({
            email, password, passwordConfirm: password, name: '부하테스트', phoneNumber: '010-1234-5678',
            place: '집', addresses: '서울시 강남구 테헤란로 1', zipCode: '06234',
        }), { headers: JSON_HEADERS });
        if (signUp.status !== 201) {
            throw new Error(`가입 실패 ${email} status=${signUp.status} body=${signUp.body}`);
        }
        const login = http.post(`${BASE_URL}/api/users/login`, JSON.stringify({ email, password }), { headers: JSON_HEADERS });
        const token = login.status === 200 ? login.json('data.accessToken') : null;
        if (!token) {
            throw new Error(`로그인 실패 ${email} status=${login.status} body=${login.body}`);
        }
        tokens.push(token);
    }
    return { tokens };
}

// 메시지 하나 = 채팅방 생성 + 메뉴 계획(fake) + 견적 발급 + 견적 카드 저장
export default function (data) {
    const token = data.tokens[(__VU - 1) % data.tokens.length];
    const res = http.post(`${BASE_URL}/api/chat/messages`, JSON.stringify({ text: '김치볶음밥 재료 준비해 줘' }), {
        headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` },
        tags: { name: 'startChat' },
    });
    check(res, {
        '201': (r) => r.status === 201,
        '견적 카드': (r) => r.json('data.message.payload.type') === 'QUOTE_CARD',
    });
}