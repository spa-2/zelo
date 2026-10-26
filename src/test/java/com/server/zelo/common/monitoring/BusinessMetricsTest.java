package com.server.zelo.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BusinessMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);

    @Test
    void 태그별로_횟수를_센다() {
        // when
        businessMetrics.count(BusinessMetric.QUOTE_EXCLUDED, "reason", "OUT_OF_STOCK");
        businessMetrics.count(BusinessMetric.QUOTE_EXCLUDED, "reason", "OUT_OF_STOCK");
        businessMetrics.count(BusinessMetric.QUOTE_EXCLUDED, "reason", "PRODUCT_NOT_FOUND");

        // then
        assertThat(meterRegistry.get("zelo.quote.excluded").tag("reason", "OUT_OF_STOCK").counter().count()).isEqualTo(2);
        assertThat(meterRegistry.get("zelo.quote.excluded").tag("reason", "PRODUCT_NOT_FOUND").counter().count()).isEqualTo(1);
    }

    @Test
    void 걸린_시간을_재고_결과는_그대로_돌려준다() {
        // when
        String result = businessMetrics.time(BusinessMetric.QUOTE_CREATE_TIME, () -> "견적");

        // then
        assertThat(result).isEqualTo("견적");
        assertThat(meterRegistry.get("zelo.quote.create.time").timer().count()).isEqualTo(1);
    }
}