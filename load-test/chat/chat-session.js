import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { accessTokenOf, login } from './users.js';

// chat-smoke.js, chat-load.js, chat-stress.js가 같이 쓰는 사용자 1명의 흐름(세션)
// 세션 = 로그인 → 방 목록 → 첫 메시지 → 이어서 4번 → 메시지 목록. 메시지가 5건이라 메시지 RPS = 분당 세션 수 × 5 ÷ 60
// 메시지는 계획서(docs/load-test/performance-test-plan.md 3-2)의 B 문장이다. 견적 요청·인분 변경·견적 수정·리뷰 질문·일반 질문이 섞여 있다
// 앱은 fake LLM으로 띄운다 (계획서 5장). 실제 OpenAI는 계정 한도(분당 토큰 200만) 때문에 목표 트래픽을 보낼 수 없다
//   fake는 문장 단어로 의도를 정하므로(zelo.llm.fake.intent-keywords) 응답 내용 대신 형식(견적 카드인지 글 답변인지)만 본다
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 목표: DAU 5만 중 피크 1시간에 1만 명, 그중 견적을 받는 50%(전체 시나리오 B 30% + C 20%)가 세션 1번, 안전계수 1.5
//   1만 × 50% ÷ 60분 × 1.5 = 세션 분당 125개(메시지 10.4 RPS). 견적 테스트(load-test/quote)와 같은 비율
//   예전 기준(DAU 1만, 피크 2시간에 2,000명, 전원 채팅)은 분당 25개(메시지 2.1 RPS)였다
export const TARGET_SESSIONS_PER_MINUTE = 125;

// 부하 테스트 시드(LoadTestUserSeedLoader)의 사용자 중 채팅은 5,001~10,000번만 쓴다
// 견적 테스트가 10,001~15,000번을 써서, 같이 돌려도 한 사용자가 메시지 제한(1분 10건)을 넘기지 않게 나눈다
// 분당 125세션이면 5,000명을 40분에 한 바퀴 돈다. 차례로 쓰므로 로그인은 이메일 70%, 소셜 30%다 (users.js)
const FIRST_USER_ID = 5001;
const USER_COUNT = 5000;

// intent는 기대 의도다. 요청 태그로도 붙여 의도별 응답 시간을 나눠 본다
// fake 의도 설정(QUOTE_EDIT:빼, LOOKUP_QUESTION:리뷰, GENERAL_QUESTION:보관)에 맞게 문장마다 빼·리뷰·보관을 넣었다
const MESSAGES = [
    { text: '김치볶음밥 재료 준비해 줘', intent: 'SHOPPING_REQUEST' },
    // 인분 변경은 새 견적이다 (MenuPlanIntent 주석)
    { text: '4인분으로 해 줘', intent: 'SHOPPING_REQUEST' },
    { text: '계란은 빼 줘', intent: 'QUOTE_EDIT' },
    { text: '이 김치 리뷰 어때?', intent: 'LOOKUP_QUESTION' },
    { text: '남은 김치볶음밥은 어떻게 보관해?', intent: 'GENERAL_QUESTION' },
];

export function session() {
    // 세션마다 다음 사용자로 넘어간다. 1분에 같은 사용자가 두 번 오지 않아 메시지 제한(1분 10건)에 걸리지 않는다
    const userId = FIRST_USER_ID + (exec.scenario.iterationInTest % USER_COUNT);
    const token = loginUser(userId);
    if (!token) {
        return;
    }
    const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };

    const rooms = http.get(`${BASE_URL}/api/chat/rooms`, { headers, tags: { name: 'getRooms' } });
    check(rooms, { '방 목록 200': (r) => r.status === 200 }, { kind: 'basic' });

    const first = MESSAGES[0];
    const start = http.post(`${BASE_URL}/api/chat/messages`, JSON.stringify({ text: first.text }), {
        headers: withIdempotencyKey(headers), tags: { name: 'startChat', intent: first.intent },
    });
    const started = check(start, { '첫 메시지 201': (r) => r.status === 201 }, { kind: 'basic' });
    const roomId = started ? start.json('data.roomId') : null;
    if (!roomId) {
        return;
    }
    checkFormat(first, payloadOf(start.json('data.message')));

    for (const message of MESSAGES.slice(1)) {
        const res = http.post(`${BASE_URL}/api/chat/rooms/${roomId}/messages`, JSON.stringify({ text: message.text }), {
            headers: withIdempotencyKey(headers), tags: { name: 'sendMessage', intent: message.intent },
        });
        const sent = check(res, { '이어서 보낸 메시지 201': (r) => r.status === 201 }, { kind: 'basic' });
        // 실패한 요청은 기본 check에서 이미 셌다. 응답을 받은 메시지만 형식을 판정한다
        if (sent) {
            checkFormat(message, payloadOf(res.json('data')));
        }
    }

    const messages = http.get(`${BASE_URL}/api/chat/rooms/${roomId}/messages`, { headers, tags: { name: 'getMessages' } });
    check(messages, { '메시지 목록 200': (r) => r.status === 200 }, { kind: 'basic' });
}

// fake에서 견적 요청은 상품이 담긴 견적 카드, 견적 수정·리뷰·일반 질문은 글 답변이다(fake 견적 수정은 카드 대신 되묻기)
// 형식이 다르면 의도가 문장 단어대로 정해지지 않았거나 서버가 다른 길로 처리한 것이다. kind:format으로 모아 판정한다 (계획서 4-2-1)
function checkFormat(message, payload) {
    const isCard = payload !== null && payload.type === 'QUOTE_CARD';
    const passed = message.intent === 'SHOPPING_REQUEST' ? isCard && payload.items.length > 0 : !isCard;
    check(null, { '응답 형식': () => passed }, { kind: 'format', intent: message.intent });
}

// payload는 JSON 객체로 오고(ChatMessageResponse의 @JsonRawValue), 글 답변이면 null이다
function payloadOf(chatMessage) {
    return chatMessage && chatMessage.payload ? chatMessage.payload : null;
}

// 이메일·소셜 모두 name:login 하나로 묶어 로그인 시간을 본다
function loginUser(userId) {
    const res = login(BASE_URL, userId, {
        headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' },
    });
    const token = accessTokenOf(res);
    check(res, { '로그인 200': () => token !== null }, { kind: 'basic' });
    return token;
}

// 화면이 [보내기]마다 새 UUID를 보내는 것과 같게 한다
function withIdempotencyKey(headers) {
    return { ...headers, 'Idempotency-Key': crypto.randomUUID() };
}
