package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.response.PaymentCardPayload;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.service.ChatMessageService;
import com.server.zelo.domain.delivery.service.DeliveryService;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentMethod;
import com.server.zelo.domain.payment.entity.PaymentMethodType;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.portone.entity.PortOnePaymentStatus;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentCommandServiceTest {

    private static final Long PAYMENT_METHOD_ID = 3L;
    private static final String BILLING_KEY = "billing-key-1";
    private static final String ORDER_NAME = "티셔츠 외 1건";
    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 10L;
    private static final Long AMOUNT = 32_000L;
    private static final String PORTONE_ID = "payment-uuid-1";
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 12, 0);
    private static final ShippingAddress SHIPPING_ADDRESS =
            ShippingAddress.of("홍길동", "010-1234-5678", "12345", "서울시 강남구");


    @Mock
    private OrderService orderService;

    @Mock
    private PortOneClient portOneClient;

    @Mock
    private PaymentService paymentService;

    @Mock
    private DeliveryService deliveryService;

    @Mock
    private OrderCommandService orderCommandService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private PaymentMethodService paymentMethodService;

    private PaymentCommandService paymentCommandService;

    // 트랜잭션 없이 콜백만 바로 실행해서 결제 확인 흐름만 검증한다
    @BeforeEach
    void setUp() {
        paymentCommandService = new PaymentCommandService(orderService, portOneClient, paymentService,
                deliveryService, chatMessageService, orderCommandService, paymentMethodService,
                TransactionOperations.withoutTransaction());
    }

    @Test
    void 포트원_결제가_완료되고_금액이_같으면_결제와_주문을_완료하고_배송을_만든다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, AMOUNT, PAID_AT));
        givenOrderedBy();

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
        then(paymentService).should().complete(PORTONE_ID, AMOUNT, PAID_AT);
        then(orderCommandService).should().completePayment(ORDER_ID);
        then(deliveryService).should().createDelivery(ORDER_ID, SHIPPING_ADDRESS);
        then(portOneClient).should(never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void 포트원_결제_금액이_다르면_포트원_결제를_취소하고_주문을_취소한다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, 1_000L, PAID_AT));
        givenOrderStatus(OrderStatus.PAYMENT_PENDING);

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.CANCELED);
        then(portOneClient).should().cancelPayment(anyString(), anyString());
        then(paymentService).should().cancel(PORTONE_ID, 1_000L);
        then(orderCommandService).should().cancel(ORDER_ID);
        then(paymentService).should(never()).complete(any(), any(), any());
        then(deliveryService).shouldHaveNoInteractions();
    }

    @Test
    void 주문이_먼저_취소된_뒤_결제가_완료되면_환불하고_결제만_취소한다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, AMOUNT, PAID_AT));
        givenOrderStatus(OrderStatus.CANCELED);

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.CANCELED);
        then(portOneClient).should().cancelPayment(anyString(), anyString());
        then(paymentService).should().cancel(PORTONE_ID, AMOUNT);
        then(paymentService).should(never()).complete(any(), any(), any());
        then(orderCommandService).shouldHaveNoInteractions();
        then(deliveryService).shouldHaveNoInteractions();
    }

    @Test
    void 포트원_결제가_실패면_결제와_주문을_실패_처리한다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.FAILED, AMOUNT, null));

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.FAILED);
        then(paymentService).should().fail(PORTONE_ID);
        then(orderCommandService).should().failPaymentIfPossible(ORDER_ID);
        then(orderCommandService).should(never()).cancelIfPossible(anyLong());
        then(portOneClient).should(never()).cancelPayment(anyString(), anyString());
        then(deliveryService).shouldHaveNoInteractions();
    }

    @Test
    void 포트원_결제가_아직_진행_중이면_아무것도_반영하지_않는다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.READY, AMOUNT, null));

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.CONFIRM_PENDING);
        then(paymentService).should(never()).complete(any(), any(), any());
        then(paymentService).should(never()).fail(any());
        then(paymentService).should(never()).cancel(any(), any());
        then(orderCommandService).shouldHaveNoInteractions();
        then(deliveryService).shouldHaveNoInteractions();
    }

    @Test
    void 이미_확인이_끝난_결제면_포트원을_조회하지_않는다() {
        // given
        Payment payment = Payment.of(ORDER_ID, PORTONE_ID, AMOUNT);
        payment.startConfirm();
        payment.complete(AMOUNT, PAID_AT);
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(payment);

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
        then(portOneClient).shouldHaveNoInteractions();
        then(orderCommandService).shouldHaveNoInteractions();
        then(deliveryService).shouldHaveNoInteractions();
    }

    @Test
    void 사용자가_확인을_요청하면_주문_소유자를_확인한_뒤_결제를_확인한다() {
        // given
        given(paymentService.getPayment(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, AMOUNT, PAID_AT));
        givenOrderedBy();

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(USER_ID, PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
        then(orderService).should().validateOwner(ORDER_ID, USER_ID);
    }

    @Test
    void 본인_주문이_아니면_ORDER_ACCESS_DENIED가_발생하고_결제를_확인하지_않는다() {
        // given
        given(paymentService.getPayment(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        willThrow(new BusinessException(ErrorCode.ORDER_ACCESS_DENIED))
                .given(orderService).validateOwner(ORDER_ID, USER_ID);

        // then
        assertThatThrownBy(() -> paymentCommandService.confirmPayment(USER_ID, PORTONE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_ACCESS_DENIED));
        then(paymentService).should(never()).startConfirm(anyString());
        then(portOneClient).shouldHaveNoInteractions();
        then(orderCommandService).shouldHaveNoInteractions();
        then(deliveryService).shouldHaveNoInteractions();
    }

    @Test
    void 포트원_결제가_취소면_결제와_주문을_취소한다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.CANCELLED, AMOUNT, null));

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.CANCELED);
        then(paymentService).should().cancel(PORTONE_ID, AMOUNT);
        then(orderCommandService).should().cancelIfPossible(ORDER_ID);
        then(orderCommandService).should(never()).failPaymentIfPossible(anyLong());
        then(paymentService).should(never()).fail(any());
        then(portOneClient).should(never()).cancelPayment(anyString(), anyString());
        then(deliveryService).shouldHaveNoInteractions();
    }

    @Test
    void 결제가_완료되면_채팅방에_결제_카드를_남긴다() {
        // given
        givenPaid();
        givenOrderedByInChatRoom(5L);

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
        then(chatMessageService).should().createPaymentCardMessage(eq(5L), eq(USER_ID), any());
    }

    @Test
    void 채팅방_없이_들어온_주문은_결제_카드를_남기지_않는다() {
        // given
        givenPaid();
        givenOrderedBy();

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
        then(chatMessageService).shouldHaveNoInteractions();
    }

    @Test
    void 결제_카드_저장이_실패해도_결제_완료를_반환한다() {
        // given
        givenPaid();
        givenOrderedByInChatRoom(5L);
        willThrow(new BusinessException(ErrorCode.INVALID_INPUT))
                .given(chatMessageService).createPaymentCardMessage(anyLong(), anyLong(), any());

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
        then(paymentService).should().complete(PORTONE_ID, AMOUNT, PAID_AT);
    }

    @Test
    void 결제_금액이_달라_환불하면_채팅방에_금액_불일치_카드를_남긴다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, 1_000L, PAID_AT));
        givenChatRoomOrder(OrderStatus.PAYMENT_PENDING, 5L);

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.CANCELED);
        PaymentCardPayload card = capturedPaymentCard(5L);
        assertThat(card.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(card.reason()).isEqualTo(PaymentCardPayload.AMOUNT_MISMATCH);
        assertThat(card.paidAmount()).isEqualTo(1_000L);
        assertThat(card.paidAt()).isNull();
    }

    @Test
    void 주문이_먼저_취소돼_환불하면_채팅방에_주문_취소_환불_카드를_남긴다() {
        // given
        givenPaid();
        givenChatRoomOrder(OrderStatus.CANCELED, 5L);

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.CANCELED);
        PaymentCardPayload card = capturedPaymentCard(5L);
        assertThat(card.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(card.reason()).isEqualTo(PaymentCardPayload.ORDER_CLOSED);
        assertThat(card.paidAmount()).isEqualTo(AMOUNT);
        assertThat(card.paidAt()).isNull();
    }

    @Test
    void 결제가_실패하면_채팅방에_실패_카드를_남긴다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.FAILED, AMOUNT, null));
        givenChatRoomOrder(5L);

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.FAILED);
        PaymentCardPayload card = capturedPaymentCard(5L);
        assertThat(card.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(card.reason()).isNull();
        assertThat(card.paidAmount()).isNull();
        assertThat(card.paidAt()).isNull();
    }

    @Test
    void 결제창에서_취소하면_채팅방에_취소_카드를_남긴다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.CANCELLED, AMOUNT, null));
        givenChatRoomOrder(5L);

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.CANCELED);
        PaymentCardPayload card = capturedPaymentCard(5L);
        assertThat(card.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(card.reason()).isEqualTo(PaymentCardPayload.USER_CANCELED);
        assertThat(card.paidAmount()).isNull();
        assertThat(card.paidAt()).isNull();
    }

    @Test
    void 저장된_카드로_결제하면_빌링키로_결제를_요청하고_포트원_재조회로_완료한다() {
        // given
        Payment payment = givenBillingPayment(OrderStatus.PAYMENT_PENDING);
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(payment);
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, AMOUNT, PAID_AT));

        // when
        PaymentStatus status = paymentCommandService.payWithPaymentMethod(USER_ID, PORTONE_ID, PAYMENT_METHOD_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
        then(paymentService).should().startBillingPayment(PORTONE_ID);
        then(portOneClient).should().payWithBillingKey(PORTONE_ID, BILLING_KEY, ORDER_NAME, AMOUNT);
        then(paymentService).should().complete(PORTONE_ID, AMOUNT, PAID_AT);
    }

    @Test
    void 카드가_거절되면_결제를_실패_처리한다() {
        // given
        givenBillingPayment(OrderStatus.PAYMENT_PENDING);
        willThrow(new BusinessException(ErrorCode.PG_REQUEST_REJECTED))
                .given(portOneClient).payWithBillingKey(PORTONE_ID, BILLING_KEY, ORDER_NAME, AMOUNT);

        // when
        PaymentStatus status = paymentCommandService.payWithPaymentMethod(USER_ID, PORTONE_ID, PAYMENT_METHOD_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.FAILED);
        then(paymentService).should().fail(PORTONE_ID);
        then(orderCommandService).should().failPaymentIfPossible(ORDER_ID);
    }

    @Test
    void 빌링키_결제_응답이_없으면_예외를_던지고_실패_처리하지_않는다() {
        // given
        givenBillingPayment(OrderStatus.PAYMENT_PENDING);
        willThrow(new BusinessException(ErrorCode.PG_TIMEOUT))
                .given(portOneClient).payWithBillingKey(PORTONE_ID, BILLING_KEY, ORDER_NAME, AMOUNT);

        // when // then
        assertThatThrownBy(() -> paymentCommandService.payWithPaymentMethod(USER_ID, PORTONE_ID, PAYMENT_METHOD_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_TIMEOUT);
        then(paymentService).should(never()).fail(any());
    }

    @Test
    void 결제_대기_중이_아닌_주문이면_예외가_발생하고_결제를_요청하지_않는다() {
        // given
        givenBillingPayment(OrderStatus.CANCELED);

        // when // then
        assertThatThrownBy(() -> paymentCommandService.payWithPaymentMethod(USER_ID, PORTONE_ID, PAYMENT_METHOD_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_ORDER_STATUS);
        then(paymentService).should(never()).startBillingPayment(any());
        then(portOneClient).should(never()).payWithBillingKey(any(), any(), any(), any());
    }

    @Test
    void 다른_사용자의_카드면_예외가_발생하고_결제를_요청하지_않는다() {
        // given
        given(paymentService.getPayment(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(paymentMethodService.getOwnedMethod(PAYMENT_METHOD_ID, USER_ID))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_METHOD_NOT_FOUND));

        // when // then
        assertThatThrownBy(() -> paymentCommandService.payWithPaymentMethod(USER_ID, PORTONE_ID, PAYMENT_METHOD_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_METHOD_NOT_FOUND);
        then(portOneClient).should(never()).payWithBillingKey(any(), any(), any(), any());
    }

    @Test
    void 이미_진행된_결제면_예외가_발생하고_결제를_요청하지_않는다() {
        // given
        given(paymentService.getPayment(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(paymentMethodService.getOwnedMethod(PAYMENT_METHOD_ID, USER_ID))
                .willReturn(PaymentMethod.of(USER_ID, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234"));
        Order order = mock(Order.class);
        given(orderService.getOrderWithItems(ORDER_ID)).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.PAYMENT_PENDING);
        given(paymentService.startBillingPayment(PORTONE_ID))
                .willThrow(new BusinessException(ErrorCode.ALREADY_PROCESSED_PAYMENT));

        // when // then
        assertThatThrownBy(() -> paymentCommandService.payWithPaymentMethod(USER_ID, PORTONE_ID, PAYMENT_METHOD_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ALREADY_PROCESSED_PAYMENT);
        then(portOneClient).should(never()).payWithBillingKey(any(), any(), any(), any());
    }

    private Payment givenBillingPayment(OrderStatus orderStatus) {
        Payment payment = Payment.of(ORDER_ID, PORTONE_ID, AMOUNT);
        given(paymentService.getPayment(PORTONE_ID)).willReturn(payment);
        given(paymentMethodService.getOwnedMethod(PAYMENT_METHOD_ID, USER_ID))
                .willReturn(PaymentMethod.of(USER_ID, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234"));
        Order order = mock(Order.class);
        given(orderService.getOrderWithItems(ORDER_ID)).willReturn(order);
        given(order.getStatus()).willReturn(orderStatus);
        if (orderStatus == OrderStatus.PAYMENT_PENDING) {
            given(order.getOrderName()).willReturn(ORDER_NAME);
        }
        return payment;
    }

    // 결제 반영 전에 주문이 결제 대기인지 확인하므로 주문 상태를 준비한다
    private Order givenOrderStatus(OrderStatus status) {
        Order order = mock(Order.class);
        given(orderService.getOrderWithItems(ORDER_ID)).willReturn(order);
        given(order.getStatus()).willReturn(status);
        return order;
    }

    // 배송은 주문에 저장된 배송지로 만들므로 결제 대기 주문과 그 배송지를 준비한다
    private Order givenOrderedBy() {
        Order order = givenOrderStatus(OrderStatus.PAYMENT_PENDING);
        given(order.getShippingAddress()).willReturn(SHIPPING_ADDRESS);
        return order;
    }

    // 결제 카드는 채팅방과 주문한 사용자를 꺼내므로 배송지에 더해 사용자와 채팅방도 준비한다
    private Order givenOrderedByInChatRoom(Long roomId) {
        Order order = givenOrderedBy();
        User user = mock(User.class);
        given(order.getUser()).willReturn(user);
        given(user.getId()).willReturn(USER_ID);
        givenChatRoom(order, roomId);
        return order;
    }

    private void givenPaid() {
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, AMOUNT, PAID_AT));
    }

    private void givenChatRoom(Order order, Long roomId) {
        ChatRoom chatRoom = mock(ChatRoom.class);
        given(order.getChatRoom()).willReturn(chatRoom);
        given(chatRoom.getId()).willReturn(roomId);
    }

    // 결제 완료가 아닌 분기는 배송을 만들지 않으므로, 결제 카드에 필요한 사용자·채팅방만 준비한다
    private void givenChatRoomOrder(OrderStatus status, Long roomId) {
        Order order = givenOrderStatus(status);
        givenOrderUserAndChatRoom(order, roomId);
    }

    // 결제 실패·결제창 취소는 주문 상태를 주문 쪽 조건부 메서드가 확인하므로 상태 없이 주문만 준비한다
    private void givenChatRoomOrder(Long roomId) {
        Order order = mock(Order.class);
        given(orderService.getOrderWithItems(ORDER_ID)).willReturn(order);
        givenOrderUserAndChatRoom(order, roomId);
    }

    private void givenOrderUserAndChatRoom(Order order, Long roomId) {
        User user = mock(User.class);
        given(order.getUser()).willReturn(user);
        given(user.getId()).willReturn(USER_ID);
        givenChatRoom(order, roomId);
    }

    private PaymentCardPayload capturedPaymentCard(Long roomId) {
        ArgumentCaptor<PaymentCardPayload> card = ArgumentCaptor.forClass(PaymentCardPayload.class);
        then(chatMessageService).should().createPaymentCardMessage(eq(roomId), eq(USER_ID), card.capture());
        return card.getValue();
    }
}