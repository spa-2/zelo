import { Rate } from 'k6/metrics';
import {
    abortVisit, authHeaders, completeVisit, countPurchaseStep, get, loginUser, pickUser, post, startVisit, think,
} from '../common.js';
import { chat, purchaseMessages } from './chat.js';

// C. 구매 (계획서 9-2 C). 사용자 20,001~25,000 (결제 시드가 결제수단과 배송지를 넣은 사용자, 계획서 3-5)
// 로그인 → 방 목록 → 메시지 3개 → 배송지 → 결제수단 → 구매 확인 → 주문 생성 → 등록 카드 결제 → 주문 상세. 요청 11개
// 결제 시드는 기본으로 이 사용자들에게 결제 대기 주문을 넣고, 결제 대기 주문이 있으면 새 주문이 409(ORDER_004)다.
// 그래서 통합 테스트는 앱을 zelo.seed.load-test.payment-order-count=0으로 띄워 결제수단만 넣는다 (계획서 3-5, 6-2)
// 다른 번호대를 써야 하면 -e PURCHASE_FIRST_USER, -e PURCHASE_USER_COUNT로 준다 (결제수단·배송지가 있어야 한다)
const FIRST_USER_ID = Number(__ENV.PURCHASE_FIRST_USER || 20001);
const USER_COUNT = Number(__ENV.PURCHASE_USER_COUNT || 5000);
// 결제 실패율 (계획서 4-1-2 결제 요청 < 0.1%). 결제 API는 실패·취소·확인 대기도 200으로 돌려줘서(PaymentController.payWithPaymentMethod)
// http_req_failed만으로는 못 잡는다. HTTP 실패이거나 data.status가 PAID가 아니면 실패로 센다
const paymentFailed = new Rate('payment_failed');

export function purchase() {
    startVisit();
    const token = loginUser(pickUser(FIRST_USER_ID, USER_COUNT));
    if (!token) {
        abortVisit('login');
        return;
    }
    countPurchaseStep('started');
    const headers = authHeaders(token);
    think();

    const result = chat(token, purchaseMessages());
    if (result.failedStep) {
        abortVisit(result.failedStep);
        return;
    }
    // 견적 카드를 하나도 받지 못하면 주문하지 않는다. HTTP 실패가 아니라 응답 형식 check 실패로 센다
    if (!result.lastCard) {
        abortVisit('noQuoteCard');
        return;
    }
    countPurchaseStep('quote_ready');
    think();

    // 주문 화면에 들어가면 배송지와 결제수단을 조회한다
    if (!get('/api/users/me/addresses', { name: 'getAddresses', api: 'read' }, { headers }).ok) {
        abortVisit('getAddresses');
        return;
    }
    think();
    const methods = get('/api/payment-methods', { name: 'getPaymentMethods', api: 'read' }, { headers });
    const paymentMethodId = methods.ok ? methods.json('data.0.paymentMethodId') : null;
    if (!paymentMethodId) {
        abortVisit('getPaymentMethods');
        return;
    }
    think();

    // [주문하기]: 구매 확인으로 주문서(기본 배송지·금액)를 받고, 그 확인 ID로 주문을 만든다 (order-pay.html)
    const quoteId = result.lastCard.quoteId;
    const confirmation = post('/api/orders/confirmations', { roomId: result.roomId, quoteId },
        { name: 'createConfirmation', api: 'write' }, { headers, expected: 201 });
    if (!confirmation.ok) {
        abortVisit('createConfirmation');
        return;
    }
    think();
    countPurchaseStep('order_tried');
    const order = post('/api/orders', { quoteId, confirmationId: confirmation.json('data.confirmationId') },
        { name: 'createOrder', api: 'write' }, { headers, expected: 201 });
    if (!order.ok) {
        abortVisit('createOrder');
        return;
    }
    countPurchaseStep('order_ok');
    think();

    countPurchaseStep('payment_tried');
    const payment = post(`/api/payments/${order.json('data.portoneId')}/payment-method`, { paymentMethodId },
        { name: 'payWithPaymentMethod', kind: 'payment' }, { headers });
    const paid = payment.ok && payment.json('data.status') === 'PAID';
    paymentFailed.add(!paid);
    if (!paid) {
        abortVisit('payment');
        return;
    }
    countPurchaseStep('payment_ok');
    think();

    const detail = get(`/api/orders/${order.json('data.orderId')}`, { name: 'getOrderDetail', api: 'read' }, { headers });
    if (!detail.ok || detail.json('data.status') !== 'PAID') {
        abortVisit('getOrderDetail');
        return;
    }
    countPurchaseStep('completed');
    completeVisit();
}
