package com.server.zelo.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.user.entity.UserRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

class LogContextInterceptorTest {

    private final LogContextInterceptor logContextInterceptor = new LogContextInterceptor();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void 로그인한_사용자와_주소의_채팅방을_MDC에_넣는다() {
        // given
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(1L, UserRole.BASIC), null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("roomId", "10"));

        // when
        logContextInterceptor.preHandle(request, response, new Object());

        // then
        assertThat(MDC.get("userId")).isEqualTo("1");
        assertThat(MDC.get("roomId")).isEqualTo("10");
    }

    @Test
    void 로그인하지_않았거나_채팅방이_없는_요청은_넣지_않는다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();

        // when
        logContextInterceptor.preHandle(request, response, new Object());

        // then
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("roomId")).isNull();
    }

    @Test
    void 요청이_끝나면_지워서_다음_요청의_로그에_남지_않게_한다() {
        // given
        MDC.put("userId", "1");
        MDC.put("roomId", "10");
        MDC.put("traceId", "abc");

        // when
        logContextInterceptor.afterCompletion(new MockHttpServletRequest(), response, new Object(), null);

        // then
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("roomId")).isNull();
        // traceId는 Micrometer Tracing이 관리하므로 건드리지 않는다
        assertThat(MDC.get("traceId")).isEqualTo("abc");
    }
}
