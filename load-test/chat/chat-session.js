import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

// chat-smoke.js, chat-load.js, chat-stress.js가 같이 쓰는 사용자 1명의 흐름(세션)
// 세션 = 로그인 → 방 목록 → 첫 메시지 → 이어서 4번 → 메시지 목록. 메시지가 5건이라 메시지 RPS = 분당 세션 수 × 5 ÷ 60
// 메시지는 계획서(docs/load-test/performance-test-plan.md 3-2)의 B 문장이다. 견적 요청·인분 변경·견적 수정·리뷰 질문·일반 질문이 섞여 있다
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// 앱을 어떤 LLM으로 띄웠는지. real이 기본이고, fake로 띄웠으면 -e LLM=fake로 돌린다
// fake는 문장과 관계없이 정해 둔 답을 돌려줘서(견적 수정은 되묻기만 함) 의도별 성공 조건을 볼 수 없다. 그래서 상태 코드만 본다
const FAKE_LLM = (__ENV.LLM || 'real') === 'fake';

// 목표: DAU 5만 중 피크 1시간에 1만 명, 그중 견적을 받는 50%(전체 시나리오 B 30% + C 20%)가 세션 1번, 안전계수 1.5
//   1만 × 50% ÷ 60분 × 1.5 = 세션 분당 125개(메시지 10.4 RPS). 견적 테스트(load-test/quote)와 같은 비율
//   예전 기준(DAU 1만, 피크 2시간에 2,000명, 전원 채팅)은 분당 25개(메시지 2.1 RPS)였다
export const TARGET_SESSIONS_PER_MINUTE = 125;

// 부하 테스트 시드(LoadTestUserSeedLoader)의 이메일 사용자 중 채팅은 5,001~10,000번만 쓴다
// 견적 테스트가 10,001~15,000번을 써서, 같이 돌려도 한 사용자가 메시지 제한(1분 10건)을 넘기지 않게 나눈다
// 분당 125세션이면 5,000명을 40분에 한 바퀴 돈다
const FIRST_USER_ID = 5001;
const USER_COUNT = 5000;
const PASSWORD = 'Zelo1234!';

// 견적 카드에서 뺀 계란 상품을 찾을 때 쓴다. 카드에는 재료 이름이 없고 상품 이름만 있어서 상품 이름으로 본다 (시드: 계란 10구, 동물복지 유정란 10구 등)
const EGG_PRODUCT = /계란|유정란/;

// intent는 기대 의도다. 요청 태그로도 붙여 의도별 응답 시간을 나눠 본다
const MESSAGES = [
    { text: '김치볶음밥 재료 준비해 줘', intent: 'SHOPPING_REQUEST' },
    // 인분 변경은 새 견적이다 (MenuPlanIntent 주석). 카드에 인분 칸이 없어서 새 견적 ID가 나왔는지로 본다
    { text: '4인분으로 해 줘', intent: 'SHOPPING_REQUEST' },
    { text: '계란은 빼 줘', intent: 'QUOTE_EDIT' },
    { text: '이 김치 리뷰 어때?', intent: 'LOOKUP_QUESTION' },
    { text: '남은 김치볶음밥은 어떻게 보관해?', intent: 'GENERAL_QUESTION' },
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
    // 이후 메시지의 성공 조건(새 견적 ID인지, 계란이 빠졌는지)은 바로 앞 견적 카드와 비교한다
    let card = payloadOf(start.json('data.message'));
    checkIntent(first, card, null);

    for (const message of MESSAGES.slice(1)) {
        const res = http.post(`${BASE_URL}/api/chat/rooms/${roomId}/messages`, JSON.stringify({ text: message.text }), {
            headers: withIdempotencyKey(headers), tags: { name: 'sendMessage', intent: message.intent },
        });
        check(res, { '이어서 보낸 메시지 201': (r) => r.status === 201 }, { kind: 'basic' });
        const payload = res.status === 201 ? payloadOf(res.json('data')) : null;
        checkIntent(message, payload, card);
        if (payload && payload.type === 'QUOTE_CARD') {
            card = payload;
        }
    }

    const messages = http.get(`${BASE_URL}/api/chat/rooms/${roomId}/messages`, { headers, tags: { name: 'getMessages' } });
    check(messages, { '메시지 목록 200': (r) => r.status === 200 }, { kind: 'basic' });
}

// 카드가 왔다고 의도대로 처리된 것은 아니다. 새 견적과 견적 수정 모두 견적 카드를 돌려주므로 의도마다 따로 본다
// kind:intent로 모아서 계획서 4-1-2·4-2-1 기준(기대와 다른 의도 5% 미만)으로 판정한다
function checkIntent(message, payload, previousCard) {
    if (FAKE_LLM) {
        return;
    }
    const isCard = payload !== null && payload.type === 'QUOTE_CARD';
    let passed;
    if (message.intent === 'SHOPPING_REQUEST') {
        passed = isCard && payload.items.length > 0
            && (previousCard === null ? payload.menuName === '김치볶음밥' : payload.quoteId !== previousCard.quoteId);
    } else if (message.intent === 'QUOTE_EDIT') {
        passed = isCard && previousCard !== null && payload.quoteId !== previousCard.quoteId
            && payload.items.every((item) => !EGG_PRODUCT.test(item.productName))
            && payload.items.length < previousCard.items.length;
    } else {
        // 리뷰·일반 질문은 견적 카드 없이 글로 답한다
        passed = !isCard;
    }
    check(null, { '기대한 의도': () => passed }, { kind: 'intent', intent: message.intent });
}

// payload는 JSON 객체로 오고(ChatMessageResponse의 @JsonRawValue), 글 답변이면 null이다
function payloadOf(chatMessage) {
    return chatMessage && chatMessage.payload ? chatMessage.payload : null;
}

function login(email) {
    const res = http.post(`${BASE_URL}/api/users/login`, JSON.stringify({ email, password: PASSWORD }), {
        headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' },
    });
    check(res, { '로그인 200': (r) => r.status === 200 }, { kind: 'basic' });
    return res.status === 200 ? res.json('data.accessToken') : null;
}

// 화면이 [보내기]마다 새 UUID를 보내는 것과 같게 한다
function withIdempotencyKey(headers) {
    return { ...headers, 'Idempotency-Key': crypto.randomUUID() };
}
