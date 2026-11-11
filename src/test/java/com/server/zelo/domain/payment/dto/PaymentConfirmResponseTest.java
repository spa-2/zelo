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
            "FAILED, 결제에 실패했어요. 결제 수단을 다시 골라 주세요.",
            "CANCELED, 결제가 취소됐어요.",
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