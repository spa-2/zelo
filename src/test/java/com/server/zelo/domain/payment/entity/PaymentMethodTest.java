package com.server.zelo.domain.payment.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentMethodTest {

    @Test
    void 결제_수단을_만들면_사용자_종류_빌링키_표시_이름이_저장된다() {
        // when
        PaymentMethod paymentMethod = PaymentMethod.of(1L, PaymentMethodType.CARD, "billing-key-1", "신한카드 1234");

        // then
        assertThat(paymentMethod.getUserId()).isEqualTo(1L);
        assertThat(paymentMethod.getType()).isEqualTo(PaymentMethodType.CARD);
        assertThat(paymentMethod.getBillingKey()).isEqualTo("billing-key-1");
        assertThat(paymentMethod.getDisplayName()).isEqualTo("신한카드 1234");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void 빌링키가_비어_있으면_예외가_발생한다(String billingKey) {
        // when // then
        assertThatThrownBy(() -> PaymentMethod.of(1L, PaymentMethodType.CARD, billingKey, "신한카드 1234"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void 사용자나_종류가_없으면_예외가_발생한다() {
        // when // then
        assertThatThrownBy(() -> PaymentMethod.of(null, PaymentMethodType.CARD, "billing-key-1", "신한카드 1234"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PaymentMethod.of(1L, null, "billing-key-1", "신한카드 1234"))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void 표시_이름이_비어_있으면_예외가_발생한다(String displayName) {
        // when // then
        assertThatThrownBy(() -> PaymentMethod.of(1L, PaymentMethodType.CARD, "billing-key-1", displayName))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }
}