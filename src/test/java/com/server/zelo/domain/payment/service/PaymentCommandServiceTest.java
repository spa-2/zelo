package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.portone.entity.PortOnePaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
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
    private OrderCommandService orderCommandService;

    private PaymentCommandService paymentCommandService;

    // 트랜잭션 없이 콜백만 바로 실행해서 결제 확인 흐름만 검증한다
    @BeforeEach
    void setUp() {
        paymentCommandService = new PaymentCommandService(orderService, portOneClient, paymentService,
                orderCommandService, TransactionOperations.withoutTransaction());
    }

    @Test
    void 포트원_결제가_완료되고_금액이_같으면_결제와_주문을_완료한다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, AMOUNT, PAID_AT));

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PAID);
        then(paymentService).should().complete(PORTONE_ID, AMOUNT, PAID_AT);
        then(orderCommandService).should().completePayment(ORDER_ID);
        then(portOneClient).should(never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void 포트원_결제_금액이_다르면_포트원_결제를_취소하고_주문을_취소한다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, 1_000L, PAID_AT));

        // when
        PaymentStatus status = paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        assertThat(status).isEqualTo(PaymentStatus.CANCELED);
        then(portOneClient).should().cancelPayment(anyString(), anyString());
        then(paymentService).should().cancel(PORTONE_ID, 1_000L);
        then(orderCommandService).should().cancel(ORDER_ID);
        then(paymentService).should(never()).complete(any(), any(), any());
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
        then(orderCommandService).should().failPayment(ORDER_ID);
        then(orderCommandService).should(never()).cancel(anyLong());
        then(portOneClient).should(never()).cancelPayment(anyString(), anyString());
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
    }

    @Test
    void 사용자가_확인을_요청하면_주문_소유자를_확인한_뒤_결제를_확인한다() {
        // given
        given(paymentService.getPayment(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, AMOUNT, PAID_AT));

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

        // when
        // then
        assertThatThrownBy(() -> paymentCommandService.confirmPayment(USER_ID, PORTONE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_ACCESS_DENIED));
        then(paymentService).should(never()).startConfirm(anyString());
        then(portOneClient).shouldHaveNoInteractions();
        then(orderCommandService).shouldHaveNoInteractions();
    }
}