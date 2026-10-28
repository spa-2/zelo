package com.server.zelo.domain.portone.dto.response;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PortOnePaymentResponseTest {

    @Test
    void 카드_결제면_카드사_이름을_반환한다() {
        // given
        PortOnePaymentResponse response = response(new PortOnePaymentResponse.Method(
                new PortOnePaymentResponse.Card("신한카드"), null, null));

        // when // then
        assertThat(response.methodName()).isEqualTo("신한카드");
    }

    @Test
    void 간편결제면_서비스_코드를_반환한다() {
        // given
        PortOnePaymentResponse response = response(new PortOnePaymentResponse.Method(null, "KAKAOPAY", null));

        // when // then
        assertThat(response.methodName()).isEqualTo("KAKAOPAY");
    }

    @Test
    void 계좌이체면_은행_코드를_반환한다() {
        // given
        PortOnePaymentResponse response = response(new PortOnePaymentResponse.Method(null, null, "SHINHAN"));

        // when // then
        assertThat(response.methodName()).isEqualTo("SHINHAN");
    }

    @Test
    void 결제_수단_정보가_없으면_ETC를_반환한다() {
        // given
        PortOnePaymentResponse response = response(null);

        // when // then
        assertThat(response.methodName()).isEqualTo(PortOnePaymentResponse.ETC);
    }

    @Test
    void 이름을_만들_값이_하나도_없으면_ETC를_반환한다() {
        // given
        PortOnePaymentResponse response = response(new PortOnePaymentResponse.Method(null, null, null));

        // when // then
        assertThat(response.methodName()).isEqualTo(PortOnePaymentResponse.ETC);
    }

    private PortOnePaymentResponse response(PortOnePaymentResponse.Method method) {
        return new PortOnePaymentResponse("payment-1", "PAID", null, null, method);
    }
}