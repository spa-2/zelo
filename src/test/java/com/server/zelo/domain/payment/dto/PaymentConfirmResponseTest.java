package com.server.zelo.domain.payment.dto;

import com.server.zelo.domain.payment.dto.response.PaymentConfirmResponse;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class PaymentConfirmResponseTest {

    @ParameterizedTest
    @CsvSource({
            "PAID, 결제가 완료됐습니다.",
            "FAILED, 결제에 실패했어요. 주문을 다시 진행해 주시길 바랍니다.",
            "CANCELED, 결제 금액이 주문 금액과 달라 결제를 취소했어요.",
            "READY, 결제를 확인하고 있어요.",
            "CONFIRM_PENDING, 결제를 확인하고 있어요."
    })
    void 결제_상태에_맞는_안내_문구를_담는다(PaymentStatus status, String message) {
        // when
        PaymentConfirmResponse response = PaymentConfirmResponse.of("payment-uuid-1", status);

        // then
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.message()).isEqualTo(message);
    }
}