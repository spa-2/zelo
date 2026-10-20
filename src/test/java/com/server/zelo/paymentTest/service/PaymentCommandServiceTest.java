package com.server.zelo.paymentTest.service;

import com.server.zelo.domain.order.mock.service.MockOrderService;
import com.server.zelo.domain.payment.client.PortOneClient;
import com.server.zelo.domain.payment.client.dto.PortOnePayment;
import com.server.zelo.domain.payment.client.PortOnePaymentStatus;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.service.PaymentCommandService;
import com.server.zelo.domain.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentCommandServiceTest {

    private static final Long ORDER_ID = 10L;
    private static final Long AMOUNT = 32_000L;
    private static final String PORTONE_ID = "payment-uuid-1";
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 12, 0);

    @Mock
    private PaymentService paymentService;

    @Mock
    private PortOneClient portOneClient;

    @Mock
    private MockOrderService mockOrderService;

    private PaymentCommandService paymentCommandService;

    // 트랜잭션 없이 콜백만 바로 실행해서 결제 확인 흐름만 검증한다
    @BeforeEach
    void setUp() {
        paymentCommandService = new PaymentCommandService(
                paymentService, portOneClient, mockOrderService, TransactionOperations.withoutTransaction());
    }

    @Test
    void 포트원_결제가_완료되고_금액이_같으면_결제와_주문을_완료한다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, AMOUNT, PAID_AT));

        // when
        paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        then(paymentService).should().complete(PORTONE_ID, AMOUNT, PAID_AT);
        then(mockOrderService).should().completePayment(ORDER_ID);
        then(portOneClient).should(never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void 포트원_결제_금액이_다르면_포트원_결제를_취소하고_주문을_취소한다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.PAID, 1_000L, PAID_AT));

        // when
        paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        then(portOneClient).should().cancelPayment(anyString(), anyString());
        then(paymentService).should().cancel(PORTONE_ID, 1_000L);
        then(mockOrderService).should().cancel(ORDER_ID);
        then(paymentService).should(never()).complete(any(), any(), any());
    }

    @Test
    void 포트원_결제가_실패면_결제를_실패_처리하고_주문을_취소한다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.FAILED, AMOUNT, null));

        // when
        paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        then(paymentService).should().fail(PORTONE_ID);
        then(mockOrderService).should().cancel(ORDER_ID);
        then(portOneClient).should(never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void 포트원_결제가_아직_진행_중이면_아무것도_반영하지_않는다() {
        // given
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(Payment.of(ORDER_ID, PORTONE_ID, AMOUNT));
        given(portOneClient.getPayment(PORTONE_ID))
                .willReturn(PortOnePayment.of(PORTONE_ID, PortOnePaymentStatus.READY, AMOUNT, null));

        // when
        paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        then(paymentService).should(never()).complete(any(), any(), any());
        then(paymentService).should(never()).fail(any());
        then(paymentService).should(never()).cancel(any(), any());
        then(mockOrderService).shouldHaveNoInteractions();
    }

    @Test
    void 이미_확인이_끝난_결제면_포트원을_조회하지_않는다() {
        // given
        Payment payment = Payment.of(ORDER_ID, PORTONE_ID, AMOUNT);
        payment.startConfirm();
        payment.complete(AMOUNT, PAID_AT);
        given(paymentService.startConfirm(PORTONE_ID)).willReturn(payment);

        // when
        paymentCommandService.confirmPayment(PORTONE_ID);

        // then
        then(portOneClient).shouldHaveNoInteractions();
        then(mockOrderService).shouldHaveNoInteractions();
    }
}