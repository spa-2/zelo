package com.server.zelo.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpFilterTest {

    private static final String HEADER = "X-Viewer-Ip";

    private final ClientIpFilter filter = new ClientIpFilter(HEADER);

    @Test
    void 헤더에_접속자_IP가_있으면_원격_주소로_바꾼다() throws Exception {
        // given
        MockHttpServletRequest request = requestFromEdge("1.2.3.4");

        // when
        String remoteAddr = remoteAddrAfterFilter(request);

        // then
        assertThat(remoteAddr).isEqualTo("1.2.3.4");
    }

    @Test
    void IPv6_주소도_그대로_쓴다() throws Exception {
        // given
        MockHttpServletRequest request = requestFromEdge("2406:da12:1a2b::1");

        // when
        String remoteAddr = remoteAddrAfterFilter(request);

        // then
        assertThat(remoteAddr).isEqualTo("2406:da12:1a2b::1");
    }

    @Test
    void 헤더가_없으면_원래_원격_주소를_쓴다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");

        // when
        String remoteAddr = remoteAddrAfterFilter(request);

        // then
        assertThat(remoteAddr).isEqualTo("10.0.0.5");
    }

    @Test
    void IP_형식이_아닌_값은_무시하고_원래_원격_주소를_쓴다() throws Exception {
        // given
        MockHttpServletRequest request = requestFromEdge("1.2.3.4\r\nx-injected: 1");

        // when
        String remoteAddr = remoteAddrAfterFilter(request);

        // then
        assertThat(remoteAddr).isEqualTo("10.0.0.5");
    }

    @Test
    void 너무_긴_값은_무시하고_원래_원격_주소를_쓴다() throws Exception {
        // given
        MockHttpServletRequest request = requestFromEdge("1".repeat(46));

        // when
        String remoteAddr = remoteAddrAfterFilter(request);

        // then
        assertThat(remoteAddr).isEqualTo("10.0.0.5");
    }

    private MockHttpServletRequest requestFromEdge(String viewerIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader(HEADER, viewerIp);
        return request;
    }

    private String remoteAddrAfterFilter(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        return ((HttpServletRequest) chain.getRequest()).getRemoteAddr();
    }
}
