import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

// chat-smoke.js, chat-load.js, chat-stress.js가 같이 쓰는 사용자 1명의 흐름(세션)
// 세션 = 로그인 → 방 목록 → 첫 메시지 → 이어서 4번 → 메시지 목록. 메시지가 5건이라 메시지 RPS = 분당 세션 수 × 5 ÷ 60
// LLM은 fake로 호출 1번에 3.5~6초(실측 메뉴 계획 p50 4.4~5.7초, 답변 p50 3.5초). 메시지 하나는 LLM 2번이라 약 9.5초, 세션 하나는 약 48초
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 목표: DAU 1만, 피크 2시간에 2,000명, 안전계수 1.5 → 세션 분당 25개(메시지 2.1 RPS)
export const TARGET_SESSIONS_PER_MINUTE = 25;

// 부하 테스트 시드(LoadTestUserSeedLoader)의 이메일 사용자 중 채팅은 5,001~15,000번만 쓴다
const FIRST_USER_ID = 5001;
const USER_COUNT = 10000;
const PASSWORD = 'Zelo1234!';

const MESSAGES = [
    '김치볶음밥 재료 준비해 줘',
    '4인분으로 해 줘',
    '맵지 않은 걸로 부탁해',
    '계란은 넉넉하게 넣어 줘',
    '이대로 다시 정리해 줘',
];

export function session() {
    // 세션마다 다음 사용자로 넘어간다. 1분에 같은 사용자가 두 번 오지 않아 메시지 제한(1분 10건)에 걸리지 않는다
    const userId = FIRST_USER_ID + (exec.scenario.iterationInTest % USER_COUNT);
    const token = login(`load${userId}@test.com`);
    if (!token) {
        return;
    }
    const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };

    const rooms = http.get(`${BASE_URL}/api/chat/rooms`, { headers, tags: { name: 'getRooms' } });
    check(rooms, { '방 목록 200': (r) => r.status === 200 });

    const start = http.post(`${BASE_URL}/api/chat/messages`, JSON.stringify({ text: MESSAGES[0] }), {
        headers: withIdempotencyKey(headers), tags: { name: 'startChat' },
    });
    const started = check(start, {
        '첫 메시지 201': (r) => r.status === 201,
        '첫 메시지 견적 카드': (r) => r.json('data.message.payload.type') === 'QUOTE_CARD',
    });
    const roomId = start.status === 201 ? start.json('data.roomId') : null;
    if (!started || !roomId) {
        return;
    }

    for (const text of MESSAGES.slice(1)) {
        const res = http.post(`${BASE_URL}/api/chat/rooms/${roomId}/messages`, JSON.stringify({ text }), {
            headers: withIdempotencyKey(headers), tags: { name: 'sendMessage' },
        });
        check(res, {
            '이어서 보낸 메시지 201': (r) => r.status === 201,
            '이어서 보낸 메시지 견적 카드': (r) => r.json('data.payload.type') === 'QUOTE_CARD',
        });
    }

    const messages = http.get(`${BASE_URL}/api/chat/rooms/${roomId}/messages`, { headers, tags: { name: 'getMessages' } });
    check(messages, { '메시지 목록 200': (r) => r.status === 200 });
}

function login(email) {
    const res = http.post(`${BASE_URL}/api/users/login`, JSON.stringify({ email, password: PASSWORD }), {
        headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' },
    });
    check(res, { '로그인 200': (r) => r.status === 200 });
    return res.status === 200 ? res.json('data.accessToken') : null;
}

// 화면이 [보내기]마다 새 UUID를 보내는 것과 같게 한다
function withIdempotencyKey(headers) {
    return { ...headers, 'Idempotency-Key': crypto.randomUUID() };
}
