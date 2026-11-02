package com.server.zelo.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.common.exception.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ApiErrorMetricsInitializerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);
    private final ApiErrorMetricsInitializer initializer = new ApiErrorMetricsInitializer(businessMetrics);

    @Test
    void 모든_에러_코드를_0으로_미리_등록한다() {
        // when
        initializer.registerAll();

        // then
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(meterRegistry.get("zelo.api.error").tag("code", code.getCode()).counter().count()).isZero();
        }
    }

    @Test
    void 미리_등록한_시계열을_이어서_올린다() {
        // given
        initializer.registerAll();

        // when
        businessMetrics.count(BusinessMetric.API_ERROR, "code", ErrorCode.INTERNAL_ERROR.getCode());

        // then
        assertThat(meterRegistry.get("zelo.api.error").tag("code", ErrorCode.INTERNAL_ERROR.getCode()).counter().count())
                .isEqualTo(1);
    }
}
