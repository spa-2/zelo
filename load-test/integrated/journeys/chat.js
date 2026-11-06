import { authHeaders, checkFormat, get, post, random, think, withIdempotencyKey } from '../common.js';

// B·C 흐름이 같이 쓰는 채팅 메시지 (계획서 3-2)
// 같은 문장만 보내면 상품 후보가 늘 같아 DB 캐시가 실제보다 잘 맞아서, 메뉴를 돌려 쓴다
// 통합은 fake LLM이라 의도는 문장 단어로 정해진다 (zelo.llm.fake.intent-keywords=QUOTE_EDIT:빼,LOOKUP_QUESTION:리뷰,GENERAL_QUESTION:보관).
// 맞는 단어가 없는 문장(견적 요청·인분 변경)은 plan-intent(SHOPPING_REQUEST)다. 그래서 문장마다 빼·리뷰·보관을 꼭 넣는다
const MENUS = [
    { menu: '김치볶음밥', remove: '계란은 빼 줘', review: '이 김치 리뷰 어때?', general: '남은 김치볶음밥은 어떻게 보관해?' },
    { menu: '된장찌개', remove: '대파는 빼 줘', review: '이 된장 리뷰 어때?', general: '남은 된장찌개는 어떻게 보관해?' },
    { menu: '김치찌개', remove: '두부는 빼 줘', review: '이 두부 리뷰 어때?', general: '남은 김치찌개는 어떻게 보관해?' },
];

// B: 견적 요청 → 인분 변경 → 견적 수정 → 리뷰 질문 → 일반 질문
export function quoteChatMessages() {
    const menu = random(MENUS);
    return [
        { text: `${menu.menu} 재료 준비해 줘`, intent: 'SHOPPING_REQUEST' },
        { text: '4인분으로 해 줘', intent: 'SHOPPING_REQUEST' },
        { text: menu.remove, intent: 'QUOTE_EDIT' },
        { text: menu.review, intent: 'LOOKUP_QUESTION' },
        { text: menu.general, intent: 'GENERAL_QUESTION' },
    ];
}

// C: 견적 요청 → 인분 변경 → 견적 수정
export function purchaseMessages() {
    const menu = random(MENUS);
    return [
        { text: `${menu.menu} 재료 준비해 줘`, intent: 'SHOPPING_REQUEST' },
        { text: '2인분으로 해 줘', intent: 'SHOPPING_REQUEST' },
        { text: menu.remove, intent: 'QUOTE_EDIT' },
    ];
}

// 방 목록 → 메시지들을 보낸다. 앞 요청이 실패하면 멈추고 실패한 단계를 돌려준다
// 돌려주는 값: { roomId, lastCard, failedStep }. lastCard는 마지막으로 받은 견적 카드(없으면 null)
// fake에서는 견적 요청이 QUOTE_CARD, 견적 수정·리뷰·일반 질문은 글 답변이다(fake 견적 수정은 카드 대신 되묻기). 이 형식만 본다 (계획서 9-1)
export function chat(token, messages) {
    const headers = authHeaders(token);
    if (!get('/api/chat/rooms', { name: 'getRooms', api: 'read' }, { headers }).ok) {
        return { failedStep: 'getRooms' };
    }
    think();

    const [first, ...rest] = messages;
    const start = post('/api/chat/messages', { text: first.text },
        { name: 'startChat', api: 'ai', intent: first.intent }, { headers: withIdempotencyKey(headers), expected: 201 });
    if (!start.ok) {
        return { failedStep: 'startChat' };
    }
    const roomId = start.json('data.roomId');
    let lastCard = cardOf(start.json('data.message'), first.intent);

    for (const message of rest) {
        think();
        const res = post(`/api/chat/rooms/${roomId}/messages`, { text: message.text },
            { name: 'sendMessage', api: 'ai', intent: message.intent }, { headers: withIdempotencyKey(headers), expected: 201 });
        if (!res.ok) {
            return { roomId, lastCard, failedStep: 'sendMessage' };
        }
        lastCard = cardOf(res.json('data'), message.intent) || lastCard;
    }
    return { roomId, lastCard, failedStep: null };
}

// payload는 JSON 객체로 오고(ChatMessageResponse의 @JsonRawValue), 글 답변이면 null이다
function cardOf(chatMessage, intent) {
    const payload = chatMessage && chatMessage.payload ? chatMessage.payload : null;
    const isCard = payload !== null && payload.type === 'QUOTE_CARD';
    checkFormat(intent === 'SHOPPING_REQUEST' ? isCard && payload.items.length > 0 : !isCard, intent);
    return isCard ? payload : null;
}
