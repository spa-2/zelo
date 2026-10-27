package com.server.zelo.domain.payment.controller;

import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.payment.entity.PaymentMethod;
import com.server.zelo.domain.payment.entity.PaymentMethodType;
import com.server.zelo.domain.payment.service.PaymentMethodCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
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
class PaymentMethodControllerTest {

    private static final String URL = "/api/payment-methods";
    private static final String BILLING_KEY = "billing-key-1";
    private static final String JSON = "{\"billingKey\":\"" + BILLING_KEY + "\"}";

    @InjectMocks
    private PaymentMethodController paymentMethodController;

    @Mock
    private PaymentMethodCommandService paymentMethodCommandService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(paymentMethodController)
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
    void 결제_수단을_등록하면_201과_종류_표시_이름을_반환한다() throws Exception {
        // given
        authenticate(1L);
        PaymentMethod paymentMethod = PaymentMethod.of(1L, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234");
        ReflectionTestUtils.setField(paymentMethod, "id", 3L);
        given(paymentMethodCommandService.createPaymentMethod(1L, BILLING_KEY)).willReturn(paymentMethod);

        // when // then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.paymentMethodId").value(3))
                .andExpect(jsonPath("$.data.type").value("CARD"))
                .andExpect(jsonPath("$.data.displayName").value("신한카드 1234"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"billingKey\":\"\"}", "{\"billingKey\":\" \"}"})
    void 빌링키가_비어_있으면_400을_반환하고_등록하지_않는다(String json) throws Exception {
        // given
        authenticate(1L);

        // when // then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        then(paymentMethodCommandService).should(never()).createPaymentMethod(anyLong(), any());
    }

    @Test
    void 빌링키가_200자를_넘으면_400을_반환한다() throws Exception {
        // given
        authenticate(1L);
        String json = "{\"billingKey\":\"" + "k".repeat(201) + "\"}";

        // when // then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
    }

    @Test
    void 인증_정보가_없으면_401을_반환하고_등록하지_않는다() throws Exception {
        // when // then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        then(paymentMethodCommandService).should(never()).createPaymentMethod(anyLong(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"INVALID_BILLING_KEY", "PAYMENT_METHOD_ALREADY_REGISTERED",
            "PG_REQUEST_FAILED", "PG_TIMEOUT"})
    void 등록에_실패하면_에러_코드에_맞는_상태와_메시지를_반환한다(ErrorCode code) throws Exception {
        // given
        authenticate(1L);
        given(paymentMethodCommandService.createPaymentMethod(1L, BILLING_KEY))
                .willThrow(new BusinessException(code));

        // when // then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(code.getMessage()));
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(userId), null, AuthorityUtils.NO_AUTHORITIES));
    }
}