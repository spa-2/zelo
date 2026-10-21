package com.server.zelo.domain.payment.controller;

import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.facade.PaymentFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    private static final String PORTONE_ID = "payment-uuid-1";

    @InjectMocks
    private PaymentController paymentController;

    @Mock
    private PaymentFacade paymentFacade;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(paymentController)
                .setCustomArgumentResolvers(new AuthArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // 테스트끼리 인증 정보가 섞이지 않게 비움
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 결제를_확인하면_결제_상태와_안내_문구를_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(paymentFacade.confirmPayment(1L, PORTONE_ID)).willReturn(PaymentStatus.PAID);

        // when // then
        mockMvc.perform(post("/api/payments/{portoneId}/confirm", PORTONE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.portoneId").value(PORTONE_ID))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.message").value("결제가 완료됐어요."));
    }

    @Test
    void 결제가_실패하면_다시_주문하라는_안내를_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(paymentFacade.confirmPayment(1L, PORTONE_ID)).willReturn(PaymentStatus.FAILED);

        // when // then
        mockMvc.perform(post("/api/payments/{portoneId}/confirm", PORTONE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.message").value("결제에 실패했어요. 주문을 다시 진행해 주세요."));
    }

    @Test
    void 아직_결제가_끝나지_않았으면_확인_중_안내를_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(paymentFacade.confirmPayment(1L, PORTONE_ID)).willReturn(PaymentStatus.CONFIRM_PENDING);

        // when // then
        mockMvc.perform(post("/api/payments/{portoneId}/confirm", PORTONE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRM_PENDING"))
                .andExpect(jsonPath("$.data.message").value("결제를 확인하고 있어요. 잠시 후 주문 내역에서 확인해 주세요."));
    }

    @Test
    void 인증_정보가_없으면_401을_반환하고_결제를_확인하지_않는다() throws Exception {
        // when // then
        mockMvc.perform(post("/api/payments/{portoneId}/confirm", PORTONE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        then(paymentFacade).should(never()).confirmPayment(anyLong(), any());
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(userId), null, AuthorityUtils.NO_AUTHORITIES));
    }
}