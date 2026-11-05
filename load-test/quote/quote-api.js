import http from 'k6/http';
import { check } from 'k6';

// 견적 API 요청 모음. 견적 부하 테스트와 다른 도메인·전체 시나리오가 같이 쓴다
//   import { login, issueQuote, changeQuantity } from '../quote/quote-api.js';
// 요청마다 kind:quote 태그가 붙어서 {kind:quote}로 견적만 따로 볼 수 있다. 로그인은 kind가 없어서 견적 측정에 안 들어간다
// 견적 발급은 채팅 첫 메시지(POST /api/chat/messages) 안에서 일어난다: 채팅방 생성 + 메뉴 계획(LLM) + 견적 발급 + 견적 설명(LLM)
// LLM은 fake로 띄워야 한다 (--zelo.llm.mode=fake --zelo.llm.fake.plan-intent=SHOPPING_REQUEST). 지연을 안 주면 서버 처리 시간만 잰다
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 부하 테스트 사용자 시드(LoadTestUserSeedLoader)의 채팅·견적 범위(5,001~15,000) 중 견적 테스트는 10,001~15,000을 쓴다
// 채팅 테스트와 같이 돌려도 겹치는 사용자가 1분에 10번(메시지 제한)을 넘기지 않게 뒤쪽 절반만 쓴다
export const QUOTE_FIRST_USER_ID = 10001;
export const QUOTE_USER_COUNT = 5000;
const PASSWORD = 'Zelo1234!';
const JSON_HEADERS = { 'Content-Type': 'application/json' };
// 견적 발급이 막혀도 요청이 오래 매달리지 않게 끊는다 (k6 기본 60초)
const TIMEOUT = __ENV.REQUEST_TIMEOUT || '30s';
const FAILURE_LOG_LIMIT = 3;
let loggedFailures = 0;

export function login(userId) {
    const res = http.post(`${BASE_URL}/api/users/login`,
        JSON.stringify({ email: `load${userId}@test.com`, password: PASSWORD }),
        { headers: JSON_HEADERS, tags: { name: 'login' } });
    check(res, { '로그인 200': (r) => r.status === 200 });
    return res.status === 200 ? res.json('data.accessToken') : null;
}

// 새 채팅방에서 첫 메시지로 견적을 받는다. 성공하면 { roomId, quote(카드 payload) }
export function issueQuote(token, text = '김치볶음밥 재료 준비해 줘') {
    const res = http.post(`${BASE_URL}/api/chat/messages`, JSON.stringify({ text }), params(token, 'issueQuote', true));
    const ok = check(res, {
        '견적 발급 201': (r) => r.status === 201,
        '견적 카드': (r) => read(r, 'data.message.payload.type') === 'QUOTE_CARD',
    }, { kind: 'quote' });
    logFailure(ok, 'issueQuote', res);
    return ok ? { roomId: res.json('data.roomId'), quote: res.json('data.message.payload') } : null;
}

// 견적 카드의 −/+ 버튼. 바뀐 카드 payload를 돌려준다 (수정할 때마다 견적 ID가 새로 나온다)
export function changeQuantity(token, roomId, quote, item, quantity) {
    return editItem(token, roomId, quote, item, quantity, 'changeQuantity');
}

// 견적 카드의 ✕ 버튼 (수량 0)
export function removeItem(token, roomId, quote, item) {
    return editItem(token, roomId, quote, item, 0, 'removeItem');
}

function editItem(token, roomId, quote, item, quantity, name) {
    const res = http.post(`${BASE_URL}/api/chat/rooms/${roomId}/quote-items`,
        JSON.stringify({ quoteId: quote.quoteId, productId: item.productId, quantity }), params(token, name, false));
    const ok = check(res, {
        [`${name} 201`]: (r) => r.status === 201,
        [`${name} 새 견적 카드`]: (r) => read(r, 'data.payload.type') === 'QUOTE_CARD'
            && read(r, 'data.payload.quoteId') !== quote.quoteId,
    }, { kind: 'quote' });
    logFailure(ok, name, res);
    return ok ? res.json('data.payload') : null;
}

function params(token, name, idempotent) {
    const headers = { ...JSON_HEADERS, Authorization: `Bearer ${token}` };
    // 화면이 [보내기]마다 새 UUID를 보내는 것과 같게 한다
    if (idempotent) {
        headers['Idempotency-Key'] = crypto.randomUUID();
    }
    return { headers, tags: { name, kind: 'quote' }, timeout: TIMEOUT };
}

function read(res, path) {
    try {
        return res.json(path);
    } catch (e) {
        return null;
    }
}

// 실패 원인을 남기려고 VU마다 처음 몇 건만 상태 코드와 본문을 찍는다. 다 찍으면 로그가 넘친다
function logFailure(ok, name, res) {
    if (!ok && loggedFailures < FAILURE_LOG_LIMIT) {
        loggedFailures++;
        console.warn(`견적 요청 실패 name=${name} status=${res.status} error=${res.error} body=${String(res.body).slice(0, 200)}`);
    }
}
