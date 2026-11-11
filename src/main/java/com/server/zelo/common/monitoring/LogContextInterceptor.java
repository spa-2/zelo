package com.server.zelo.common.monitoring;

import com.server.zelo.common.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

// 요청의 사용자·채팅방을 MDC에 넣어 그 요청의 모든 로그에 찍히게 한다 (traceId·spanId는 Micrometer Tracing이 넣는다).
// 지표 태그에는 넣지 않는다: 값이 사용자 수만큼 늘어나 Prometheus가 감당하지 못한다. 그래서 로그로만 찾는다
@Component
public class LogContextInterceptor implements HandlerInterceptor {

    public static final String USER_ID = "userId";
    public static final String ROOM_ID = "roomId";

    // 인증은 이보다 앞선 시큐리티 필터가 끝내므로, 여기서는 SecurityContext에 들어 있는 사용자를 읽기만 한다
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUser authUser) {
            MDC.put(USER_ID, String.valueOf(authUser.id()));
        }
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> pathVariables
                && pathVariables.get(ROOM_ID) instanceof String roomId) {
            MDC.put(ROOM_ID, roomId);
        }
        return true;
    }

    // 톰캣은 스레드를 다시 쓰므로 지우지 않으면 다음 요청 로그에 이전 사용자가 찍힌다
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MDC.remove(USER_ID);
        MDC.remove(ROOM_ID);
    }
}
