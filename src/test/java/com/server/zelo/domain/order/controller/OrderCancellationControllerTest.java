package com.server.zelo.domain.order.controller;
import com.server.zelo.domain.user.entity.UserRole;
import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.order.dto.response.CancelOrderResponse;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.stream.Stream;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청 검증·인증 리졸버·예외 처리를 확인하며 JWT 필터나 실제 결제 서버는 실행하지 않는다.
@ExtendWith(MockitoExtension.class)
class OrderCancellationControllerTest {

    private static final Long USER_ID = 7L;
    private static final Long ORDER_ID = 101L;
    private static final String URL = "/api/orders/101/cancellations";
    private static final String JSON = "{\"reason\":\"고객 요청\"}";

    @Mock private OrderFacade facade;
    @Mock private OrderCommandService command;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(facade, command))
                .setCustomArgumentResolvers(new AuthArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증된_사용자의_주문을_취소하고_200과_주문상태를_반환한다() throws Exception {
        // given
        authenticate();
        given(facade.cancelBeforePayment(USER_ID, ORDER_ID))
                .willReturn(new CancelOrderResponse(ORDER_ID, OrderStatus.CANCELED));

        // when
        // then
        mockMvc.perform(post(URL).param("userId", "999")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(101))
                .andExpect(jsonPath("$.data.orderStatus").value("CANCELED"))
                .andExpect(jsonPath("$.data.refundId").doesNotExist())
                .andExpect(jsonPath("$.data.refundStatus").doesNotExist());
        then(facade).should().cancelBeforePayment(USER_ID, ORDER_ID);
        verifyNoInteractions(command);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void 취소_사유의_길이_경계값을_허용한다(int length) throws Exception {
        // given
        authenticate();
        given(facade.cancelBeforePayment(USER_ID, ORDER_ID))
                .willReturn(new CancelOrderResponse(ORDER_ID, OrderStatus.CANCELED));

        // when
        // then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "가".repeat(length) + "\"}"))
                .andExpect(status().isOk());
        then(facade).should().cancelBeforePayment(USER_ID, ORDER_ID);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void 취소_사유가_없거나_유효하지_않으면_400으로_응답한다(String json) throws Exception {
        // given
        authenticate();

        // when
        // then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        verifyNoInteractions(facade,command);
    }

    static Stream<String> invalidRequests() {
        return Stream.of("{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}",
                "{\"reason\":\"" + "가".repeat(51) + "\"}", "{", "");
    }

    @Test
    void 인증정보가_없으면_401로_응답하고_취소하지_않는다() throws Exception {
        // given
        SecurityContextHolder.clearContext();

        // when
        // then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        verifyNoInteractions(facade,command);
    }

    @Test
    void 주문_ID가_숫자가_아니면_400으로_응답한다() throws Exception {
        // given
        authenticate();

        // when
        // then
        mockMvc.perform(post("/api/orders/invalid/cancellations")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        verifyNoInteractions(facade, command);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"ORDER_NOT_FOUND", "ORDER_ACCESS_DENIED",
            "INVALID_ORDER_STATUS", "TIMEOUT_EXCEPTION"})
    void 취소_실패는_해당_HTTP_상태와_코드로_응답한다(ErrorCode code) throws Exception {
        // given
        authenticate();
        given(facade.cancelBeforePayment(USER_ID, ORDER_ID)).willThrow(new BusinessException(code));

        // when
        // then
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(command);
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                AuthUser.of(USER_ID, UserRole.BASIC), null, AuthorityUtils.NO_AUTHORITIES));
    }
}
