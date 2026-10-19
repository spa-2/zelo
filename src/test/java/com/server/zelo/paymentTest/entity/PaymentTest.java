package com.server.zelo.paymentTest.entity;

import com.server.zelo.payments.entity.Payment;
import com.server.zelo.payments.entity.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

}