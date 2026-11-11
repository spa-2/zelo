package com.server.zelo.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class TraceIdResponseFilterTest {

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    @Mock
    private TraceContext traceContext;

    @InjectMocks
    private TraceIdResponseFilter traceIdResponseFilter;

    @Test
    void 지금_요청의_traceId를_응답_헤더에_담는다() throws ServletException, IOException {
        // given
        given(tracer.currentSpan()).willReturn(span);
        given(span.context()).willReturn(traceContext);
        given(traceContext.traceId()).willReturn("6a1f0c2b9d3e4f50");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        traceIdResponseFilter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        // then
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("6a1f0c2b9d3e4f50");
    }

    @Test
    void 추적_중이_아니면_헤더_없이_그대로_넘긴다() throws ServletException, IOException {
        // given
        given(tracer.currentSpan()).willReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // when
        traceIdResponseFilter.doFilter(new MockHttpServletRequest(), response, filterChain);

        // then
        assertThat(response.getHeader("X-Trace-Id")).isNull();
        assertThat(filterChain.getRequest()).isNotNull();
    }
}
