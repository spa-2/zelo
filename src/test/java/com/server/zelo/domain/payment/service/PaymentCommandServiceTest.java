package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.service.ChatMessageService;
import com.server.zelo.domain.delivery.service.DeliveryService;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.portone.entity.PortOnePaymentStatus;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import com.server.zelo.domain.user.service.UserAddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 10L;
    private static final Long AMOUNT = 32_000L;
    private static final String PORTONE_ID = "payment-uuid-1";
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 12, 0);

    @Mock
    private OrderService orderService;

    @Mock
    private PortOneClient portOneClient;

    @Mock
    private PaymentService paymentService;

    @Mock
    private DeliveryService deliveryService;

    @Mock
    private UserAddressService userAddressService;

    @Mock
    private OrderCommandService orderCommandService;

    @Mock
    private ChatMessageService chatMessageService;

    private PaymentCommandService paymentCommandService;

    // 트랜잭션 없이 콜백만 바로 실행해서 결제 확인 흐름만 검증한다
    @BeforeEach
    void setUp() {
        paymentCommandService = new PaymentCommandService(orderService, portOneClient, paymentService,
                deliveryService, chatMessageService, userAddressService, orderCommandService,
                TransactionOperations.withoutTransaction());
    }

    @Test
    void 포트원_결제가_완료되고_금액이_같으면_결제와_주문을_완료하고_배송을_만든다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, AMOUNT, PAID_AT));
        givenOrderedBy(USER_ID);
        UserAddress address = mock(UserAddress.class);
        given(userAddressService.getDefaultAddress(USER_ID)).willReturn(address);

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
        then(paymentService).should().complete(PORTONE_ID, AMOUNT, PAID_AT);
        then(orderCommandService).should().completePayment(ORDER_ID);
        then(deliveryService).should().createDelivery(ORDER_ID, address);
        then(portOneClient).should(never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void 기본_배송지가_없으면_결제_완료_처리가_실패한다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, AMOUNT, PAID_AT));
        givenOrderedBy(USER_ID);
        given(userAddressService.getDefaultAddress(USER_ID))
                .willThrow(new BusinessException(ErrorCode.DEFAULT_ADDRESS_NOT_FOUND));

        // when
        // then
        // 실제로는 트랜잭션이 롤백되어 결제·주문 완료도 반영되지 않는다
        assertThatThrownBy(() -> paymentCommandService.confirmPayment(PORTONE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DEFAULT_ADDRESS_NOT_FOUND));
        then(deliveryService).shouldHaveNoInteractions();
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
        givenOrderedBy(USER_ID);
        given(userAddressService.getDefaultAddress(USER_ID)).willReturn(mock(UserAddress.class));

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
        Order order = givenOrderedByWithAddress();
        givenChatRoom(order, 5L);

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
        givenOrderedByWithAddress();

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
        Order order = givenOrderedByWithAddress();
        givenChatRoom(order, 5L);
        willThrow(new BusinessException(ErrorCode.INVALID_INPUT))
                .given(chatMessageService).createPaymentCardMessage(anyLong(), anyLong(), any());

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
        then(paymentService).should().complete(PORTONE_ID, AMOUNT, PAID_AT);
    }

    // 배송 생성 시 주문에서 주문한 사용자를 꺼내므로 그 조회 결과를 준비한다
    // 결제 반영 전에 주문이 결제 대기인지 확인하므로 주문 상태를 준비한다
    private Order givenOrderStatus(OrderStatus status) {
        Order order = mock(Order.class);
        given(orderService.getOrderWithItems(ORDER_ID)).willReturn(order);
        given(order.getStatus()).willReturn(status);
        return order;
    }

    // 배송 생성 시 주문에서 주문한 사용자를 꺼내므로 그 조회 결과를 준비한다
    private void givenOrderedBy(Long userId) {
        Order order = givenOrderStatus(OrderStatus.PAYMENT_PENDING);
        User user = mock(User.class);
        given(order.getUser()).willReturn(user);
        given(user.getId()).willReturn(userId);
    }

    private void givenPaid() {
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, AMOUNT, PAID_AT));
    }

    // 배송 생성과 결제 카드가 같은 주문에서 사용자·채팅방을 꺼내므로 주문을 돌려줘 채팅방을 더 준비할 수 있게 한다
    private Order givenOrderedByWithAddress() {
        Order order = givenOrderStatus(OrderStatus.PAYMENT_PENDING);
        User user = mock(User.class);
        given(order.getUser()).willReturn(user);
        given(user.getId()).willReturn(USER_ID);
        given(userAddressService.getDefaultAddress(USER_ID)).willReturn(mock(UserAddress.class));
        return order;
    }

    private void givenChatRoom(Order order, Long roomId) {
        ChatRoom chatRoom = mock(ChatRoom.class);
        given(order.getChatRoom()).willReturn(chatRoom);
        given(chatRoom.getId()).willReturn(roomId);
    }
}