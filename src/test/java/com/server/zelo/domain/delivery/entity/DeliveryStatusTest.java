package com.server.zelo.domain.delivery.entity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryStatusTest {

    @ParameterizedTest
    @CsvSource({
            "READY, SHIPPING",
            "READY, CANCELED",
            "SHIPPING, DELIVERED"
    })
    void 허용된_상태로는_전이할_수_있다(DeliveryStatus from, DeliveryStatus to) {
        // when
        boolean result = from.canTransitTo(to);

        // then
        assertThat(result).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "READY, READY",
            "READY, DELIVERED",
            "SHIPPING, READY",
            "SHIPPING, SHIPPING",
            "SHIPPING, CANCELED"
    })
    void 허용되지_않은_상태로는_전이할_수_없다(DeliveryStatus from, DeliveryStatus to) {
        // when
        boolean result = from.canTransitTo(to);

        // then
        assertThat(result).isFalse();
    }

    @ParameterizedTest
    @EnumSource(DeliveryStatus.class)
    void 배송_완료와_취소는_더_이상_전이할_수_없다(DeliveryStatus target) {
        // when
        boolean fromDelivered = DeliveryStatus.DELIVERED.canTransitTo(target);
        boolean fromCanceled = DeliveryStatus.CANCELED.canTransitTo(target);

        // then
        assertThat(fromDelivered).isFalse();
        assertThat(fromCanceled).isFalse();
    }
}