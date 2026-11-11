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
    @Test
    void 동시_작업은_합산하고_예외가_나도_진행수를_복원한다() throws Exception {
        // given
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.function.Supplier<Void> work = () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("failure");
        };
        try {
            // when
            var first = executor.submit(() -> businessMetrics.track("test.inflight", work));
            var second = executor.submit(() -> businessMetrics.track("test.inflight", work));
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // then
            assertThat(meterRegistry.get("test.inflight").gauge().value()).isEqualTo(2);
            release.countDown();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> first.get(3, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> second.get(3, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(meterRegistry.get("test.inflight").gauge().value()).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}