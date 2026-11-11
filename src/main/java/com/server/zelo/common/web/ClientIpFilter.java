package com.server.zelo.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

// CloudFront 뒤에서는 X-Forwarded-For 끝에 CloudFront 엣지 IP가 붙어서 getRemoteAddr()가 접속자가 아니라 엣지 IP가 된다.
// 로그인·이메일 인증 횟수 제한이 이 값을 키로 쓰므로, CloudFront 함수가 넣어 준 헤더(클라이언트가 보낸 값은 함수가 덮어써서 위조할 수 없다)로 바꿔 준다.
// 프로퍼티가 없는 환경(로컬, 부하 테스트)에서는 등록되지 않는다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnProperty(name = "zelo.client-ip.header")
public class ClientIpFilter extends OncePerRequestFilter {

    // 값이 Redis 키에 들어가므로 IPv4, IPv6 문자만 받는다
    private static final Pattern IP_PATTERN = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private final String headerName;

    public ClientIpFilter(@Value("${zelo.client-ip.header}") String headerName) {
        this.headerName = headerName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String clientIp = request.getHeader(headerName);

        if (clientIp == null || !IP_PATTERN.matcher(clientIp).matches()) {
            chain.doFilter(request, response);
            return;
        }

        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getRemoteAddr() {
                return clientIp;
            }
        }, response);
    }
}
