package com.server.zelo.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ChatRequestMetricsFilterTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ChatRequestMetricsFilter filter = new ChatRequestMetricsFilter(registry);

    @Test
    void 처리중에는_증가하고_거절응답도_완료시간에_포함한다() throws Exception {
        // given
        var request = new MockHttpServletRequest("POST", "/api/chat/rooms/123/messages");
        var response = new MockHttpServletResponse();
        // when
        filter.doFilter(request, response, (req, res) -> {
            assertThat(registry.get("zelo.chat.inflight").gauge().value()).isEqualTo(1);
            response.setStatus(429);
        });
        // then
        assertThat(registry.get("zelo.chat.inflight").gauge().value()).isZero();
        assertThat(registry.get("zelo.chat.request").tags("operation", "SEND", "status", "429").timer().count()).isEqualTo(1);
    }

    @Test
    void 처리되지_않은_예외에서도_진행수를_복원한다() {
        // given
        var request = new MockHttpServletRequest("POST", "/api/chat/messages");
        // when
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("failure");
        })).isInstanceOf(ServletException.class);
        // then
        assertThat(registry.get("zelo.chat.inflight").gauge().value()).isZero();
        assertThat(registry.get("zelo.chat.request").tags("operation", "START", "status", "500").timer().count()).isEqualTo(1);
    }

    @Test
    void 조회는_전송_지표에_섞지_않는다() throws Exception {
        // given
        var request = new MockHttpServletRequest("GET", "/api/chat/rooms/123/messages");
        // when
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        // then
        assertThat(registry.find("zelo.chat.request").timer()).isNull();
    }
    @Test
    void 다른_메시지의_재시도도_같은_시계열로_모은다() throws Exception {
        // given
        for (int id = 1; id <= 2; id++) {
            var request = new MockHttpServletRequest("POST", "/api/chat/rooms/123/messages/" + id + "/retry");
            // when
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        }
        // then
        assertThat(registry.get("zelo.chat.request").tag("operation", "RETRY").timer().count()).isEqualTo(2);
        assertThat(registry.find("zelo.chat.request").timers()).hasSize(1);
    }
}
