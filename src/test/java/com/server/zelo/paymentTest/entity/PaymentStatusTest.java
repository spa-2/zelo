package com.server.zelo.paymentTest.entity;

import com.server.zelo.domain.payment.entity.PaymentStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    @ParameterizedTest
    @CsvSource({
            "READY, CONFIRM_PENDING",
            "READY, FAILED",
            "CONFIRM_PENDING, PAID",
            "CONFIRM_PENDING, FAILED",
            "CONFIRM_PENDING, CANCELED",
            "PAID, CANCELED"
    })
    void 허용된_상태로는_전이할_수_있다(PaymentStatus from, PaymentStatus to) {
        // when
        boolean result = from.canTransitTo(to);

        // then
        assertThat(result).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "READY, READY",
            "READY, PAID",
            "READY, CANCELED",
            "CONFIRM_PENDING, READY",
            "CONFIRM_PENDING, CONFIRM_PENDING",
            "PAID, READY",
            "PAID, CONFIRM_PENDING",
            "PAID, PAID",
            "PAID, FAILED"
    })
    void 허용되지_않은_상태로는_전이할_수_없다(PaymentStatus from, PaymentStatus to) {
        // when
        boolean result = from.canTransitTo(to);

        // then
        assertThat(result).isFalse();
    }

    // 실패와 취소는 종료 상태라 어떤 상태로도 전이할 수 없다
    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void 종료_상태에서는_어떤_상태로도_전이할_수_없다(PaymentStatus to) {
        // when // then
        assertThat(PaymentStatus.FAILED.canTransitTo(to)).isFalse();
        assertThat(PaymentStatus.CANCELED.canTransitTo(to)).isFalse();
    }
}