package com.server.zelo.global.security;

import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.entity.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authorization = request.getHeader("Authorization");

        // 토큰이 필요한 요청에 토큰이 없을 경우
        if (authorization == null || authorization.isEmpty() ) {
            request.setAttribute("exception", ErrorCode.UNAUTHORIZED);
            filterChain.doFilter(request, response);
            return;
        }
        // 토큰을 추출하는데 문제가 있을 경우 우리 서비스가 사용하는 토큰이 아님
        String token;
        try {
            token = jwtUtil.substringToken(authorization);
        } catch (BusinessException e) {
            request.setAttribute("exception", ErrorCode.INVALID_TOKEN);
            filterChain.doFilter(request, response);
            return;
        }
        // 토큰을 검증하는데 문제가 있음
        if (!jwtUtil.validateToken(token)) {
            request.setAttribute("exception", ErrorCode.INVALID_TOKEN);
            filterChain.doFilter(request, response);
            return;
        }

        // 액세스 토큰이 아님 (리프레시 토큰으로 API를 호출한 경우)
        if (!jwtUtil.isAccessToken(token)) {
            request.setAttribute("exception", ErrorCode.INVALID_TOKEN);
            filterChain.doFilter(request, response);
            return;
        }

        // 유저의 정보를 준비
        Long id = jwtUtil.getUserId(token);
        UserRole userRole = jwtUtil.getUserRole(token);
        AuthUser authUser = AuthUser.of(id, userRole);
        GrantedAuthority grantedAuthority = new SimpleGrantedAuthority(
                "ROLE_" + userRole.name());
        Collection<? extends GrantedAuthority> authorities = List.of(grantedAuthority);

        // ContextHolder에 넣음
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        authUser, null,authorities
                )
        );

        filterChain.doFilter(request, response);
    }
}
