package com.server.zelo.common.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// 도메인 코드에서 비즈니스 지표를 한 줄로 남길 때 씀
// ex) businessMetrics.count(BusinessMetric.QUOTE_EXCLUDED, "reason", "OUT_OF_STOCK");
@Component
public class BusinessMetrics {

    private final MeterRegistry meterRegistry;

    // Actuator가 아직 없으면 등록된 MeterRegistry가 없어서 전역 레지스트리를 씀 (값은 안 모이지만 코드는 그대로 돌아감)
    @Autowired
    public BusinessMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        this(meterRegistry.getIfAvailable(() -> Metrics.globalRegistry));
    }

    public BusinessMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void count(BusinessMetric metric, String... tags) {
        Counter.builder(metric.getKey())
                .description(metric.getDescription())
                .tags(tags)
                .register(meterRegistry)
                .increment();
    }

    public <T> T time(BusinessMetric metric, Supplier<T> task, String... tags) {
        return Timer.builder(metric.getKey())
                .description(metric.getDescription())
                .tags(tags)
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry)
                .record(task);
    }
}