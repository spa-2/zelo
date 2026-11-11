package com.server.zelo.common.monitoring;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 응답 헤더에 traceId를 담는다. 사용자가 오류를 알려 오면 화면이 받은 이 값으로 그 요청의 로그만 모아 볼 수 있다.
// traceId는 Spring이 요청마다 여는 관측(ServerHttpObservationFilter, HIGHEST_PRECEDENCE + 1)이 만들므로 그 바로 뒤에서 읽는다
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdResponseFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final Tracer tracer;

    // 응답 본문을 쓰기 시작하면 헤더를 바꿀 수 없어, 컨트롤러로 넘기기 전에 넣는다
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null) {
            response.setHeader(TRACE_ID_HEADER, span.context().traceId());
        }
        filterChain.doFilter(request, response);
    }
}
