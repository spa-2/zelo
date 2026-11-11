package com.server.zelo.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PrometheusMetricsTest {
    @Test
    void LLM과_채팅_시간은_합산가능한_버킷으로_노출한다() {
        // given
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var metrics = new BusinessMetrics(registry);
            // when
            for (var metric : new BusinessMetric[]{BusinessMetric.LLM_CALL, BusinessMetric.LLM_ATTEMPT, BusinessMetric.CHAT_REPLY}) {
                metrics.record(metric, Duration.ofSeconds(35), "outcome", "SUCCESS");
            }
            // then
            String scrape = registry.scrape();
            assertThat(scrape).contains("zelo_llm_call_seconds_bucket", "zelo_llm_attempt_seconds_bucket", "zelo_chat_reply_seconds_bucket");
            assertThat(scrape).contains("zelo_llm_call_seconds_count{outcome=\"SUCCESS\"} 1");
            assertThat(scrape).contains("zelo_llm_call_seconds_sum{outcome=\"SUCCESS\"} 35.0");
        } finally {
            registry.close();
        }
    }
}
