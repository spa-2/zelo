package com.server.zelo.global.security;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.HandlerExceptionResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class JwtAccessDeniedHandlerTest {

    @Mock
    private HandlerExceptionResolver resolver;

    @InjectMocks
    private JwtAccessDeniedHandler accessDeniedHandler;

    @Test
    void 권한이_없으면_NO_AUTHORITY_예외를_넘긴다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        accessDeniedHandler.handle(request, response, new AccessDeniedException("test"));

        // then
        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        then(resolver).should().resolveException(eq(request), eq(response), isNull(), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) captor.getValue()).getErrorCode()).isEqualTo(ErrorCode.NO_AUTHORITY);
    }
}
