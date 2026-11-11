package com.server.zelo.global.security;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.servlet.HandlerExceptionResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationEntryPointTest {


    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @Mock
    private HandlerExceptionResolver resolver;
    @InjectMocks
    private JwtAuthenticationEntryPoint entryPoint;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    void 필터가_심어둔_에러코드가_있으면_해당_코드로_예외를_넘긴다() {
        // given
        request.setAttribute("exception", ErrorCode.INVALID_TOKEN);

        // when
        entryPoint.commence(request, response, new BadCredentialsException("test"));

        // then
        assertThat(resolvedErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void 에러코드가_없으면_UNAUTHORIZED로_예외를_넘긴다() {
        // given

        // when
        entryPoint.commence(request, response, new BadCredentialsException("test"));

        // then
        assertThat(resolvedErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    private ErrorCode resolvedErrorCode() {
        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        then(resolver).should().resolveException(
                eq(request),
                eq(response),
                isNull(),
                captor.capture());
        assertThat(captor.getValue()).isInstanceOf(BusinessException.class);
        return ((BusinessException) captor.getValue()).getErrorCode();
    }
}
