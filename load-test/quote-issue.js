import http from 'k6/http';
import { check } from 'k6';

// 견적 발급 부하 테스트 (#244)
// 앱은 LLM fake 모드로 띄워서 OpenAI 대기 시간을 빼고 서버 쪽 처리 시간만 잼
// k6 run -e BASE_URL=http://localhost:8080 -e VUS=50 load-test/quote-issue.js
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 10);

export const options = {
    scenarios: {
        quote: {
            executor: 'constant-vus',
            vus: VUS,
            duration: __ENV.DURATION || '1m',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        checks: ['rate>0.99'],
        // setup의 가입·로그인은 빼고 견적 발급 요청만 봄
        'http_req_duration{name:startChat}': ['p(95)<1000'],
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
        http.post(`${BASE_URL}/api/users`, JSON.stringify({
            email, password, passwordConfirm: password, name: '부하테스트', phoneNumber: '010-1234-5678',
            place: '집', addresses: '서울시 강남구 테헤란로 1', zipCode: '06234',
        }), { headers: JSON_HEADERS });
        const login = http.post(`${BASE_URL}/api/users/login`, JSON.stringify({ email, password }), { headers: JSON_HEADERS });
        tokens.push(login.json('data.accessToken'));
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