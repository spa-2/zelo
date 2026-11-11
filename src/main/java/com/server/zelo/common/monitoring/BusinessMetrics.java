package com.server.zelo.common.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// 도메인 코드에서 비즈니스 지표를 한 줄로 남길 때 씀
// ex) businessMetrics.count(BusinessMetric.QUOTE_EXCLUDED, "reason", "OUT_OF_STOCK");
@Component
public class BusinessMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<List<String>, AtomicInteger> active = new ConcurrentHashMap<>();

    // Actuator가 아직 없으면 등록된 MeterRegistry가 없어서 전역 레지스트리를 씀 (값은 안 모이지만 코드는 그대로 돌아감)
    @Autowired
    public BusinessMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        this(meterRegistry.getIfAvailable(() -> Metrics.globalRegistry));
    }

    public BusinessMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // 작업 스레드가 실제로 끝날 때 내려야 타임아웃 후 남은 작업도 관측된다.
    public void registerActive(String name, String... tags) {
        activeCounter(name, tags);
    }

    private AtomicInteger activeCounter(String name, String... tags) {
        var key = new ArrayList<String>(List.of(tags));
        key.add(0, name);
        return active.computeIfAbsent(List.copyOf(key), ignored -> {
            AtomicInteger counter = new AtomicInteger();
            Gauge.builder(name, counter, AtomicInteger::get).tags(tags).register(meterRegistry);
            return counter;
        });
    }

    public <T> T track(String name, Supplier<T> task, String... tags) {
        AtomicInteger value = activeCounter(name, tags);
        value.incrementAndGet();
        try {
            return task.get();
        } finally {
            value.decrementAndGet();
        }
    }

    public void count(BusinessMetric metric, String... tags) {
        Counter.builder(metric.getKey())
                .description(metric.getDescription())
                .tags(tags)
                .register(meterRegistry)
                .increment();
    }

    // 값을 올리지 않고 0으로 미리 등록만 함. 처음 나타난 시계열은 increase()가 첫 증가를 놓쳐서, 드물게 나는 지표에 씀
    public void register(BusinessMetric metric, String... tags) {
        Counter.builder(metric.getKey())
                .description(metric.getDescription())
                .tags(tags)
                .register(meterRegistry);
    }

    // 토큰 수처럼 1이 아닌 양을 더할 때 씀
    public void count(BusinessMetric metric, double amount, String... tags) {
        Counter.builder(metric.getKey())
                .description(metric.getDescription())
                .tags(tags)
                .register(meterRegistry)
                .increment(amount);
    }

    // 결과(성공·실패 코드)를 태그로 붙여야 해서 일이 끝난 뒤에 시간을 남길 때 씀
    public void record(BusinessMetric metric, Duration duration, String... tags) {
        timer(metric, tags).register(meterRegistry).record(duration);
    }

    public <T> T time(BusinessMetric metric, Supplier<T> task, String... tags) {
        return timer(metric, tags).register(meterRegistry).record(task);
    }

    private Timer.Builder timer(BusinessMetric metric, String... tags) {
        Timer.Builder builder = Timer.builder(metric.getKey()).description(metric.getDescription()).tags(tags);
        if (metric == BusinessMetric.LLM_CALL || metric == BusinessMetric.LLM_ATTEMPT
                || metric == BusinessMetric.CHAT_REPLY) {
            return builder.publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofMinutes(2));
        }
        return builder.publishPercentiles(0.5, 0.95);
    }
}
