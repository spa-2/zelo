package com.server.zelo.domain.user.social;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.user.entity.SocialProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class SocialApiMetrics {

    private final BusinessMetrics businessMetrics;

    public <T> T measure(SocialProvider provider, String operation, Supplier<T> supplier) {
        long start = System.nanoTime();
        String outcome = "SUCCESS";
        try {
            return supplier.get();
        } catch (BusinessException e) {
            outcome = e.getErrorCode().getCode();
            throw e;
        } catch (RuntimeException e) {
            outcome = "UNKNOWN";
            throw e;
        } finally {
            businessMetrics.record(BusinessMetric.SOCIAL_API_CALL,
                    Duration.ofNanos(System.nanoTime() - start),
                    "provider", provider.name(),
                    "operation", operation,
                    "outcome", outcome
            );
        }
    }
}
