import exec from 'k6/execution';
import { abortVisit, authHeaders, completeVisit, get, loginUser, pickUser, post, startVisit, think } from '../common.js';

// D. 내 주문 확인 (계획서 9-2 D). 사용자 25,001~27,000 (환불 시드가 결제 완료 주문을 넣은 사용자, 계획서 3-5)
// 로그인 → 주문 목록 → 주문 상세 → (5번 중 1번) 환불. 요청 평균 3.2개
// 사용자를 번호 순서대로 돌려 써서 같은 주문을 두 번 환불하지 않는다 (25분에 D 방문 약 560회 < 2,000명)
const FIRST_USER_ID = 25001;
const USER_COUNT = 2000;
const REFUND_EVERY = 5;

export function myOrders() {
    startVisit();
    const token = loginUser(pickUser(FIRST_USER_ID, USER_COUNT));
    if (!token) {
        abortVisit('login');
        return;
    }
    const headers = authHeaders(token);
    think();

    const orders = get('/api/orders', { name: 'getOrders', api: 'read' }, { headers });
    const first = orders.ok ? orders.json('data.content.0') : null;
    if (!first) {
        abortVisit('getOrders');
        return;
    }
    think();

    const detail = get(`/api/orders/${first.orderId}`, { name: 'getOrderDetail', api: 'read' }, { headers });
    if (!detail.ok) {
        abortVisit('getOrderDetail');
        return;
    }

    // 환불 시드 주문은 결제 완료·출고 전이라 환불할 수 있다. items를 비우면 남은 상품 전부를 환불한다
    if (exec.scenario.iterationInTest % REFUND_EVERY === 0 && detail.json('data.status') === 'PAID') {
        think();
        const refund = post(`/api/orders/${first.orderId}/refund`, { reason: '부하 테스트 환불' },
            { name: 'refund', api: 'write' }, { headers });
        if (!refund.ok || refund.json('data.status') !== 'COMPLETED') {
            abortVisit('refund');
            return;
        }
    }
    completeVisit();
}
