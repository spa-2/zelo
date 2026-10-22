package com.server.zelo.domain.payment.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentTest {

    @Test
    @DisplayName("결제를 생성하면 READY 상태이고 승인 정보는 비어 있다")
    void create() {
        Payment payment = Payment.of(1L, "payment-uuid-1", 10_000L);

        assertThat(payment.getOrderId()).isEqualTo(1L);
        assertThat(payment.getPortoneId()).isEqualTo("payment-uuid-1");
        assertThat(payment.getAmount()).isEqualTo(10_000L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(payment.getPgAmount()).isNull();
        assertThat(payment.getPaidAt()).isNull();
    }

    @Test
    void 확인을_시작하면_확인_대기_상태가_된다() {
        // given
        Payment payment = Payment.of(1L, "payment-uuid-1", 10_000L);

        // when
        payment.startConfirm();

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRM_PENDING);
        assertThat(payment.isConfirmed()).isFalse();
    }

    @Test
    void 결제를_완료하면_PG_금액과_결제_시각을_저장한다() {
        // given
        Payment payment = Payment.of(1L, "payment-uuid-1", 10_000L);
        LocalDateTime paidAt = LocalDateTime.of(2026, 10, 19, 12, 0);
        payment.startConfirm();

        // when
        payment.complete(10_000L, paidAt);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPgAmount()).isEqualTo(10_000L);
        assertThat(payment.getPaidAt()).isEqualTo(paidAt);
        assertThat(payment.isConfirmed()).isTrue();
    }

    @Test
    void 결제를_실패_처리하면_실패_상태가_된다() {
        // given
        Payment payment = Payment.of(1L, "payment-uuid-1", 10_000L);
        payment.startConfirm();

        // when
        payment.fail();

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.isConfirmed()).isTrue();
    }

    @Test
    void 금액_불일치로_취소하면_실제_결제_금액을_남긴다() {
        // given
        Payment payment = Payment.of(1L, "payment-uuid-1", 10_000L);
        payment.startConfirm();

        // when
        payment.cancel(1_000L);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(payment.getPgAmount()).isEqualTo(1_000L);
        assertThat(payment.isConfirmed()).isTrue();
    }

    @Test
    void 확인_대기가_아닌_결제를_완료하면_예외가_발생한다() {
        // given
        Payment payment = Payment.of(1L, "payment-uuid-1", 10_000L);

        // when // then
        assertThatThrownBy(() -> payment.complete(10_000L, LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_PAYMENT_STATUS);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    void 결제_금액과_PG_금액이_같은지_확인한다() {
        // given
        Payment payment = Payment.of(1L, "payment-uuid-1", 10_000L);

        // when // then
        assertThat(payment.isAmountMatched(10_000L)).isTrue();
        assertThat(payment.isAmountMatched(1_000L)).isFalse();
    }

}