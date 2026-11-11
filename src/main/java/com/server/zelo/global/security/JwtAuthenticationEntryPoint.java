package com.server.zelo.global.security;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver resolver;

    // lombok이 @Qualifier를 생성자로 복사하지 않아서 생성자를 직접 작성함
    public JwtAuthenticationEntryPoint(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) {
        // 필터가 심어둔 실패 원인을 사용, 필터를 거치지 않은 경우엔 null이라 인증 필요로 처리
        ErrorCode errorCode = (ErrorCode) request.getAttribute("exception");
        if (errorCode == null) {
            errorCode = ErrorCode.UNAUTHORIZED;
        }

        // 필터 단계의 예외는 @RestControllerAdvice에 안 닿아서 resolver로 GlobalExceptionHandler에 넘김
        resolver.resolveException(request, response, null, new BusinessException(errorCode));
    }
}
