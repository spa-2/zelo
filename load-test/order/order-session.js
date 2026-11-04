import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

// order-smoke.js, order-load.js, order-stress.js가 같이 쓰는 주문 1건의 흐름(세션)
// 세션 = 로그인 → 주문 확인 → 주문 생성 → 주문 상세 조회. 로그인을 빼면 요청 3개
// 주문 시드(LoadTestOrderQuoteSeedLoader)가 사용자마다 채팅방 1개와 견적 1개를 만들어 둔다. 사용자 번호만으로 요청을 만들 수 있어 CSV가 없다
// 결제 대기 주문은 사용자당 1건이라 세션마다 다음 사용자를 쓰고, 단계마다 앱을 재시작해 시드를 초기화한다
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 목표: 피크 2,000명 × 주문 20% × 3회 ÷ 7,200초 × 1.5 = 0.25 RPS → 세션 분당 5개 (0.25 ÷ 3 × 60)
export const TARGET_SESSIONS_PER_MINUTE = 5;

// 주문 범위는 15,001~20,000이라 전부 이메일 가입 사용자다
const FIRST_USER_ID = 15001;
const USER_COUNT = 5000;
// 주문 시드의 채팅방 ID는 이 값 + 사용자 ID, 견적 ID는 LT-ORD-{사용자 ID 7자리}
const ROOM_ID_START = 90000000;
const PASSWORD = 'Zelo1234!';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function session() {
    const index = exec.scenario.iterationInTest;
    if (index >= USER_COUNT) {
        exec.test.abort('주문 시드 사용자를 다 씀');
    }
    const userId = FIRST_USER_ID + index;
    const token = login(userId);
    if (!token) {
        return;
    }
    const params = (name) => ({
        headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` },
        tags: { name, kind: 'order' },
    });
    const quoteId = `LT-ORD-${String(userId).padStart(7, '0')}`;

    // 주문하기를 누르면 주문서(배송지·금액)를 먼저 확인한다
    const confirmation = http.post(`${BASE_URL}/api/orders/confirmations`,
        JSON.stringify({ roomId: ROOM_ID_START + userId, quoteId }), params('createConfirmation'));
    if (!check(confirmation, { '주문 확인 201': (r) => r.status === 201 })) {
        return;
    }

    const order = http.post(`${BASE_URL}/api/orders`,
        JSON.stringify({ quoteId, confirmationId: confirmation.json('data.confirmationId') }), params('createOrder'));
    if (!check(order, {
        '주문 생성 201': (r) => r.status === 201,
        '결제 대기': (r) => r.json('data.status') === 'PAYMENT_PENDING',
    })) {
        return;
    }

    // 결제 결과 카드가 주문 상세를 조회한다
    const detail = http.get(`${BASE_URL}/api/orders/${order.json('data.orderId')}`, params('getOrderDetail'));
    check(detail, { '주문 상세 200': (r) => r.status === 200 });
}

// 로그인은 주문 측정에서 빼려고 kind 태그를 붙이지 않는다
function login(userId) {
    const res = http.post(`${BASE_URL}/api/users/login`,
        JSON.stringify({ email: `load${userId}@test.com`, password: PASSWORD }),
        { headers: JSON_HEADERS, tags: { name: 'login' } });
    check(res, { '로그인 200': (r) => r.status === 200 });
    return res.status === 200 ? res.json('data.accessToken') : null;
}
