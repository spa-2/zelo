package com.server.zelo.domain.portone.dto.response;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.PaymentMethodType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortOneBillingKeyTest {

    private static final PortOneBillingKeyResponse.Customer CUSTOMER = new PortOneBillingKeyResponse.Customer("1");
    private static final PortOneBillingKeyResponse.Card CARD =
            new PortOneBillingKeyResponse.Card("신한카드", "53275000****1234");

    @Test
    void 카드_빌링키면_카드_이름과_번호_끝_4자리로_표시_이름을_만든다() {
        // given
        PortOneBillingKeyResponse response = response("ISSUED",
                new PortOneBillingKeyResponse.Method("BillingKeyPaymentMethodCard", CARD));

        // when
        PortOneBillingKey billingKey = PortOneBillingKey.from(response);

        // then
        assertThat(billingKey.type()).isEqualTo(PaymentMethodType.CARD);
        assertThat(billingKey.displayName()).isEqualTo("신한카드 1234");
        assertThat(billingKey.isOwnedBy(1L)).isTrue();
    }

    @Test
    void 카드_이름이_없으면_카드로_표시한다() {
        // given
        PortOneBillingKeyResponse response = response("ISSUED", new PortOneBillingKeyResponse.Method(
                "BillingKeyPaymentMethodCard", new PortOneBillingKeyResponse.Card(null, "53275000****1234")));

        // when // then
        assertThat(PortOneBillingKey.from(response).displayName()).isEqualTo("카드 1234");
    }

    @Test
    void 삭제된_빌링키면_예외가_발생한다() {
        // given
        PortOneBillingKeyResponse response = response("DELETED",
                new PortOneBillingKeyResponse.Method("BillingKeyPaymentMethodCard", CARD));

        // when // then
        assertThatThrownBy(() -> PortOneBillingKey.from(response))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_BILLING_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BillingKeyPaymentMethodTransfer", "BillingKeyPaymentMethodEasyPay"})
    void 카드가_아닌_빌링키면_예외가_발생한다(String type) {
        // given
        PortOneBillingKeyResponse response = response("ISSUED", new PortOneBillingKeyResponse.Method(type, null));

        // when // then
        assertThatThrownBy(() -> PortOneBillingKey.from(response))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_BILLING_KEY);
    }

    @Test
    void 발급_고객이_다른_사용자면_본인_빌링키가_아니다() {
        // given
        PortOneBillingKey billingKey = PortOneBillingKey.of("billing-key-1", PaymentMethodType.CARD, "신한카드 1234", "2");

        // when // then
        assertThat(billingKey.isOwnedBy(1L)).isFalse();
    }

    private PortOneBillingKeyResponse response(String status, PortOneBillingKeyResponse.Method method) {
        return new PortOneBillingKeyResponse(status, "billing-key-1", List.of(method), CUSTOMER);
    }
}