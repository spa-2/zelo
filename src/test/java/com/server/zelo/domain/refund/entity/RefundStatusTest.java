package com.server.zelo.domain.refund.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class RefundStatusTest {

    @ParameterizedTest
    @CsvSource({
            "REQUESTED, COMPLETED",
            "REQUESTED, FAILED"
    })
    void 허용된_상태로는_전이할_수_있다(RefundStatus from, RefundStatus to) {
        // when
        boolean result = from.canTransitTo(to);

        // then
        assertThat(result).isTrue();
    }

    @ParameterizedTest
    @EnumSource(RefundStatus.class)
    void 환불_완료와_실패는_더_이상_전이할_수_없다(RefundStatus target) {
        // when
        boolean fromCompleted = RefundStatus.COMPLETED.canTransitTo(target);
        boolean fromFailed = RefundStatus.FAILED.canTransitTo(target);

        // then
        assertThat(fromCompleted).isFalse();
        assertThat(fromFailed).isFalse();
    }

    @Test
    void 환불_요청에서_환불_요청으로는_전이할_수_없다() {
        // when
        boolean result = RefundStatus.REQUESTED.canTransitTo(RefundStatus.REQUESTED);

        // then
        assertThat(result).isFalse();
    }
}