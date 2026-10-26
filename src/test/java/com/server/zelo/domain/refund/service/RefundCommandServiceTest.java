package com.server.zelo.domain.refund.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.delivery.service.DeliveryService;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.service.PaymentService;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.portone.entity.PortOnePaymentStatus;
import com.server.zelo.domain.refund.dto.response.CreateRefundResponse;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.entity.RefundType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class RefundCommandServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 2L;
    private static final Long REFUND_ID = 10L;
    private static final String PORTONE_ID = "payment-1";
    private static final String REASON = "단순 변심";

    @Mock
    private OrderService orderService;
    @Mock private PortOneClient portOneClient;
    @Mock private RefundService refundService;
    @Mock private PaymentService paymentService;
    @Mock private DeliveryService deliveryService;
    @Mock private OrderCommandService orderCommandService;

    private RefundCommandService refundCommandService;

    @BeforeEach
    void setUp() {
        refundCommandService = new RefundCommandService(orderService, portOneClient, refundService, paymentService,
                deliveryService, orderCommandService, TransactionOperations.withoutTransaction());
    }

    @Test
    void 포트원_취소에_성공하면_환불_완료와_주문_배송_취소를_한다() {
        // given
        givenRefundable();

        // when
        CreateRefundResponse response = refundCommandService.createRefund(USER_ID, ORDER_ID, REASON, Map.of());

        // then
        assertThat(response.status()).isEqualTo(RefundStatus.COMPLETED);
        then(deliveryService).should().holdForRefund(ORDER_ID);
        then(refundService).should().complete(eq(REFUND_ID), any());
        then(paymentService).should().cancel(PORTONE_ID, 10_000L);
        then(orderCommandService).should().cancelItems(ORDER_ID, Map.of(1L, 2));
        then(deliveryService).should().cancelDelivery(ORDER_ID);
    }

    @Test
    void 포트원_취소가_거절되면_환불_실패로_두고_배송_보류를_푼다() {
        // given
        givenRefundable();
        willThrow(new BusinessException(ErrorCode.PG_REQUEST_REJECTED))
                .given(portOneClient).cancelPaymentWithAmount(any(), any(), any(), any(), any());

        // when
        CreateRefundResponse response = refundCommandService.createRefund(USER_ID, ORDER_ID, REASON, Map.of());

        // then
        assertThat(response.status()).isEqualTo(RefundStatus.FAILED);
        then(refundService).should().fail(REFUND_ID);
        then(deliveryService).should().releaseHold(ORDER_ID);
        then(orderCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 포트원_응답이_지연되면_환불_요청과_배송_보류를_유지한다() {
        // given
        givenRefundable();
        willThrow(new BusinessException(ErrorCode.PG_TIMEOUT))
                .given(portOneClient).cancelPaymentWithAmount(any(), any(), any(), any(), any());

        // when
        CreateRefundResponse response = refundCommandService.createRefund(USER_ID, ORDER_ID, REASON, Map.of());

        // then
        assertThat(response.status()).isEqualTo(RefundStatus.REQUESTED);
        then(refundService).should(never()).fail(any());
        then(deliveryService).should(never()).releaseHold(any());
    }

    @Test
    void 결제_완료가_아니면_환불할_수_없다() {
        // given
        given(paymentService.getLatestPayment(ORDER_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, 10_000L));

        // when // then
        assertThatThrownBy(() -> refundCommandService.createRefund(USER_ID, ORDER_ID, REASON, Map.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_NOT_REFUNDABLE);
        then(deliveryService).shouldHaveNoInteractions();
    }

    @Test
    void 진행_중인_환불이_있으면_배송을_보류하지_않고_예외가_발생한다() {
        // given
        given(paymentService.getLatestPayment(ORDER_ID)).willReturn(paidPayment());
        willThrow(new BusinessException(ErrorCode.REFUND_IN_PROGRESS))
                .given(refundService).validateNotInProgress(1L);

        // when // then
        assertThatThrownBy(() -> refundCommandService.createRefund(USER_ID, ORDER_ID, REASON, Map.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFUND_IN_PROGRESS);
        then(deliveryService).shouldHaveNoInteractions();
        then(portOneClient).shouldHaveNoInteractions();
    }

    @Test
    void 포트원_서버_오류면_환불_요청과_배송_보류를_유지한다() {
        // given
        givenRefundable();
        willThrow(new BusinessException(ErrorCode.PG_REQUEST_FAILED))
                .given(portOneClient).cancelPaymentWithAmount(any(), any(), any(), any(), any());

        // when
        CreateRefundResponse response = refundCommandService.createRefund(USER_ID, ORDER_ID, REASON, Map.of());

        // then
        assertThat(response.status()).isEqualTo(RefundStatus.REQUESTED);
        then(refundService).should(never()).fail(any());
        then(deliveryService).should(never()).releaseHold(any());
    }

    @Test
    void 포트원에서_취소된_결제에_진행_중_환불이_있으면_환불을_완료하고_주문과_배송을_취소한다() {
        // given
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.CANCELLED, 10_000L, null));
        given(paymentService.getPayment(PORTONE_ID)).willReturn(paidPayment());
        Refund refund = Refund.of(1L, RefundType.FULL, REASON);
        ReflectionTestUtils.setField(refund, "id", REFUND_ID);
        given(refundService.findRequestedRefund(1L)).willReturn(Optional.of(refund));
        given(deliveryService.cancelDeliveryIfPossible(ORDER_ID)).willReturn(true);

        // when
        refundCommandService.syncCancellation(ORDER_ID, PORTONE_ID);

        // then
        then(refundService).should().complete(eq(REFUND_ID), any());
        then(paymentService).should().cancel(PORTONE_ID, 10_000L);
        then(orderCommandService).should().cancelIfPossible(ORDER_ID);
        then(deliveryService).should().cancelDeliveryIfPossible(ORDER_ID);
    }

    @Test
    void 환불_요청_없이_포트원에서_취소된_결제도_주문과_배송을_취소한다() {
        // given
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.CANCELLED, 10_000L, null));
        given(paymentService.getPayment(PORTONE_ID)).willReturn(paidPayment());
        given(refundService.findRequestedRefund(1L)).willReturn(Optional.empty());
        given(deliveryService.cancelDeliveryIfPossible(ORDER_ID)).willReturn(true);

        // when
        refundCommandService.syncCancellation(ORDER_ID, PORTONE_ID);

        // then
        then(refundService).should(never()).complete(any(), any());
        then(paymentService).should().cancel(PORTONE_ID, 10_000L);
        then(orderCommandService).should().cancelIfPossible(ORDER_ID);
        then(deliveryService).should().cancelDeliveryIfPossible(ORDER_ID);
    }

    @Test
    void 포트원에서_결제가_그대로_완료_상태면_진행_중_환불을_실패로_확정하고_배송_보류를_푼다() {
        // given
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, 10_000L, null));
        given(paymentService.getPayment(PORTONE_ID)).willReturn(paidPayment());
        Refund refund = Refund.of(1L, RefundType.FULL, REASON);
        ReflectionTestUtils.setField(refund, "id", REFUND_ID);
        given(refundService.findRequestedRefund(1L)).willReturn(Optional.of(refund));

        // when
        refundCommandService.syncCancellation(ORDER_ID, PORTONE_ID);

        // then
        then(refundService).should().fail(REFUND_ID);
        then(deliveryService).should().releaseHold(ORDER_ID);
        then(paymentService).should(never()).cancel(any(), any());
        then(orderCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 포트원에서_결제가_완료_상태이고_진행_중_환불이_없으면_아무것도_바꾸지_않는다() {
        // given
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, 10_000L, null));
        given(paymentService.getPayment(PORTONE_ID)).willReturn(paidPayment());
        given(refundService.findRequestedRefund(1L)).willReturn(Optional.empty());

        // when
        refundCommandService.syncCancellation(ORDER_ID, PORTONE_ID);

        // then
        then(refundService).should(never()).fail(any());
        then(deliveryService).shouldHaveNoInteractions();
        then(orderCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 포트원에서_취소도_완료도_아닌_결제면_아무것도_바꾸지_않는다() {
        // given
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PARTIAL_CANCELLED, 10_000L, null));

        // when
        refundCommandService.syncCancellation(ORDER_ID, PORTONE_ID);

        // then
        then(paymentService).shouldHaveNoInteractions();
        then(orderCommandService).shouldHaveNoInteractions();
        then(deliveryService).shouldHaveNoInteractions();
    }

    @Test
    void 이미_취소까지_반영된_결제면_다시_바꾸지_않는다() {
        // given
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.CANCELLED, 10_000L, null));
        Payment payment = paidPayment();
        payment.cancel(10_000L);
        given(paymentService.getPayment(PORTONE_ID)).willReturn(payment);

        // when
        refundCommandService.syncCancellation(ORDER_ID, PORTONE_ID);

        // then
        then(refundService).shouldHaveNoInteractions();
        then(orderCommandService).shouldHaveNoInteractions();
        then(deliveryService).shouldHaveNoInteractions();
    }

    @Test
    void 이미_출고된_주문이면_결제만_취소하고_주문과_재고는_그대로_둔다() {
        // given
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.CANCELLED, 10_000L, null));
        given(paymentService.getPayment(PORTONE_ID)).willReturn(paidPayment());
        given(refundService.findRequestedRefund(1L)).willReturn(Optional.empty());
        given(deliveryService.cancelDeliveryIfPossible(ORDER_ID)).willReturn(false);

        // when
        refundCommandService.syncCancellation(ORDER_ID, PORTONE_ID);

        // then
        then(paymentService).should().cancel(PORTONE_ID, 10_000L);
        then(deliveryService).should().cancelDeliveryIfPossible(ORDER_ID);
        then(orderCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 일부_수량만_환불하면_그만큼만_취소하고_배송_보류를_푼다() {
        // given
        givenRefundable();

        // when
        CreateRefundResponse response = refundCommandService.createRefund(USER_ID, ORDER_ID, REASON, Map.of(1L, 1));

        // then
        assertThat(response.status()).isEqualTo(RefundStatus.COMPLETED);
        then(portOneClient).should().cancelPaymentWithAmount(
                PORTONE_ID, "refund-cancel-000000000010", 5_000L, 10_000L, REASON);
        then(orderCommandService).should().cancelItems(ORDER_ID, Map.of(1L, 1));
        then(deliveryService).should().releaseHold(ORDER_ID);
        then(paymentService).should(never()).cancel(any(), any());
        then(deliveryService).should(never()).cancelDelivery(any());
    }

    @Test
    void 부분_환불_뒤_남은_상품을_환불하면_남은_결제_금액을_취소하고_결제와_배송을_취소한다() {
        // given
        givenRefundable(1);
        given(refundService.calculateRefundedAmount(1L)).willReturn(5_000L);

        // when
        refundCommandService.createRefund(USER_ID, ORDER_ID, REASON, Map.of());

        // then
        then(portOneClient).should().cancelPaymentWithAmount(
                PORTONE_ID, "refund-cancel-000000000010", 5_000L, 5_000L, REASON);
        then(orderCommandService).should().cancelItems(ORDER_ID, Map.of(1L, 1));
        then(paymentService).should().cancel(PORTONE_ID, 10_000L);
        then(deliveryService).should().cancelDelivery(ORDER_ID);
    }

    @Test
    void 주문에_없는_상품을_환불하면_예외가_발생하고_포트원을_호출하지_않는다() {
        // given
        OrderItem orderItem = mock(OrderItem.class);
        given(orderItem.getId()).willReturn(1L);
        given(orderItem.getCancelableQuantity()).willReturn(2);
        Order order = mock(Order.class);
        given(order.getOrderItems()).willReturn(List.of(orderItem));
        given(paymentService.getLatestPayment(ORDER_ID)).willReturn(paidPayment());
        given(orderService.getOrderWithItems(ORDER_ID)).willReturn(order);

        // when // then
        assertThatThrownBy(() -> refundCommandService.createRefund(USER_ID, ORDER_ID, REASON, Map.of(99L, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);
        then(portOneClient).shouldHaveNoInteractions();
    }

    private OrderItem givenRefundable() {
        return givenRefundable(2);
    }

    private OrderItem givenRefundable(int cancelableQuantity) {
        OrderItem orderItem = mock(OrderItem.class);
        given(orderItem.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2);
        given(orderItem.getCancelableQuantity()).willReturn(cancelableQuantity);
        given(orderItem.getUnitPrice()).willReturn(5_000L);
        Order order = mock(Order.class);
        given(order.getOrderItems()).willReturn(List.of(orderItem));

        given(paymentService.getLatestPayment(ORDER_ID)).willReturn(paidPayment());
        given(orderService.getOrderWithItems(ORDER_ID)).willReturn(order);
        given(refundService.createRefund(any())).willAnswer(invocation -> {
            Refund refund = invocation.getArgument(0);
            ReflectionTestUtils.setField(refund, "id", REFUND_ID);
            return refund;
        });
        return orderItem;
    }

    private Payment paidPayment() {
        Payment payment = Payment.of(ORDER_ID, PORTONE_ID, 10_000L);
        ReflectionTestUtils.setField(payment, "id", 1L);
        payment.startConfirm();
        payment.complete(10_000L, LocalDateTime.now());
        return payment;
    }
}