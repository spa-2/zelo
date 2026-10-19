package com.server.zelo.paymentTest.controller;

import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.payment.controller.PaymentController;
import com.server.zelo.domain.payment.dto.response.CreatePaymentResponse;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.facade.PaymentFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 10L;

    @InjectMocks
    private PaymentController paymentController;

    @Mock
    private PaymentFacade paymentFacade;

    private MockMvc mockMvc;

    private Long loginUserId;

    // 스프링 컨텍스트 없이 컨트롤러, 테스트용 @Auth 리졸버, 예외 처리만 붙여서 요청과 응답 형식을 확인한다
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(paymentController)
                .setCustomArgumentResolvers(new TestAuthArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 로그인한_사용자가_결제를_생성하면_결제_정보를_반환한다() throws Exception {
        // given
        loginUserId = USER_ID;
        CreatePaymentResponse response = new CreatePaymentResponse(
                1L, ORDER_ID, "payment-uuid-1", "김치볶음밥 재료", 32_000L, PaymentStatus.READY);
        given(paymentFacade.createPayment(USER_ID, ORDER_ID)).willReturn(response);

        // when // then
        mockMvc.perform(post("/api/payments/{orderId}", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.paymentId").value(1))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.portoneId").value("payment-uuid-1"))
                .andExpect(jsonPath("$.data.orderName").value("김치볶음밥 재료"))
                .andExpect(jsonPath("$.data.amount").value(32_000))
                .andExpect(jsonPath("$.data.status").value("READY"));
    }

    @Test
    void 로그인하지_않으면_401을_반환한다() throws Exception {
        // when // then
        mockMvc.perform(post("/api/payments/{orderId}", ORDER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        then(paymentFacade).should(never()).createPayment(any(), any());
    }

    @Test
    void 결제할_수_없는_주문이면_409를_반환한다() throws Exception {
        // given
        loginUserId = USER_ID;
        given(paymentFacade.createPayment(USER_ID, ORDER_ID))
                .willThrow(new BusinessException(ErrorCode.ORDER_NOT_PAYABLE));

        // when // then
        mockMvc.perform(post("/api/payments/{orderId}", ORDER_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_NOT_PAYABLE.getCode()));
    }

    // 실제 AuthArgumentResolver 동작은 AuthArgumentResolverTest에서 검증하므로, 여기서는 로그인 사용자만 주입한다
    private class TestAuthArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(Auth.class);
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            if (loginUserId == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            return AuthUser.of(loginUserId);
        }
    }
}