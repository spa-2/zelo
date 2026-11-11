package com.server.zelo.common.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ChatRequestMetricsFilter extends OncePerRequestFilter {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger active = new AtomicInteger();

    public ChatRequestMetricsFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("zelo.chat.inflight", active, AtomicInteger::get).register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return operation(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean failed = true;
        active.incrementAndGet();
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            active.decrementAndGet();
            Timer.builder("zelo.chat.request")
                    .tags("operation", operation(request), "status", failed ? "500" : String.valueOf(response.getStatus()))
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofMinutes(2))
                    .register(meterRegistry)
                    .record(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    // 생 ID를 태그로 넣지 않고 API 종류만 구분한다. 현재 동기 MVC 요청 전체를 측정한다.
    private static String operation(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/api/chat/messages")) {
            return "START";
        }
        if (path.matches("/api/chat/rooms/[^/]+/messages")) {
            return "SEND";
        }
        if (path.matches("/api/chat/rooms/[^/]+/messages/[^/]+/retry")) {
            return "RETRY";
        }
        return null;
    }
}
