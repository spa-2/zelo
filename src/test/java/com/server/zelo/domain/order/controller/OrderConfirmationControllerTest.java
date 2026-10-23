package com.server.zelo.domain.order.controller;

import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.order.dto.request.CreateOrderConfirmationRequest;
import com.server.zelo.domain.order.dto.response.CreateOrderConfirmationResponse;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderService;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 서비스는 Mock으로 두고 실제 요청 바인딩·검증·인증 리졸버·공통 예외 처리를 실행한다.
@ExtendWith(MockitoExtension.class)
class OrderConfirmationControllerTest {

    private static final Long USER_ID = 7L;
    private static final String CONFIRMATION_ID = "23b36845-19aa-45d9-87c0-0b4bb8c30390";
    private static final CreateOrderConfirmationRequest REQUEST = new CreateOrderConfirmationRequest(10L, "QUOTE-114");
    private static final String REQUEST_JSON = "{\"roomId\":10,\"quoteId\":\"QUOTE-114\"}";

    @Mock
    private OrderService orderService;

    @Mock
    private OrderCommandService orderCommandService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService, orderCommandService))
                .setCustomArgumentResolvers(new AuthArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증된_사용자의_요청으로_확인_정보를_발급하고_201과_공통_응답을_반환한다() throws Exception {
        // given
        authenticate();
        given(orderCommandService.createOrder(USER_ID, REQUEST)).willReturn(response(REQUEST));

        // when
        // then
        mockMvc.perform(post("/api/orders/confirmations")
                        .param("userId", "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.confirmationId").value(CONFIRMATION_ID))
                .andExpect(jsonPath("$.data.roomId").value(10))
                .andExpect(jsonPath("$.data.quoteId").value("QUOTE-114"))
                .andExpect(jsonPath("$.data.totalAmount").value(10_500))
                .andExpect(jsonPath("$.data.addressSummary").value("집 · 서울특별시 강남구"))
                .andExpect(jsonPath("$.data.expiresAt").exists());
        then(orderCommandService).should().createOrder(USER_ID, REQUEST);
        then(orderCommandService).shouldHaveNoMoreInteractions();
        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void 채팅방_ID_1과_견적_ID_길이의_허용_경계값을_받는다(int quoteIdLength) throws Exception {
        // given
        authenticate();
        String quoteId = "Q".repeat(quoteIdLength);
        CreateOrderConfirmationRequest request = new CreateOrderConfirmationRequest(1L, quoteId);
        given(orderCommandService.createOrder(USER_ID, request)).willReturn(response(request));

        // when
        // then
        mockMvc.perform(post("/api/orders/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":1,\"quoteId\":\"" + quoteId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roomId").value(1))
                .andExpect(jsonPath("$.data.quoteId").value(quoteId));
        then(orderCommandService).should().createOrder(USER_ID, request);
        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void 잘못된_요청은_400과_INVALID_INPUT을_반환하고_발급하지_않는다(String body) throws Exception {
        // given
        authenticate();

        // when
        // then
        mockMvc.perform(post("/api/orders/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(orderCommandService, orderService);
    }

    @Test
    void 요청_본문이_없으면_400과_INVALID_INPUT을_반환한다() throws Exception {
        // given
        authenticate();

        // when
        // then
        mockMvc.perform(post("/api/orders/confirmations").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        verifyNoInteractions(orderCommandService, orderService);
    }

    @Test
    void JSON_형식이_깨졌으면_400과_INVALID_INPUT을_반환한다() throws Exception {
        // given
        authenticate();

        // when
        // then
        mockMvc.perform(post("/api/orders/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        verifyNoInteractions(orderCommandService, orderService);
    }

    @Test
    void 인증_정보가_없으면_401이며_확인_정보를_발급하지_않는다() throws Exception {
        // given
        SecurityContextHolder.clearContext();

        // when
        // then
        mockMvc.perform(post("/api/orders/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        verifyNoInteractions(orderCommandService, orderService);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {
            "CHAT_ROOM_NOT_FOUND", "CHAT_ROOM_ACCESS_DENIED", "NO_AUTHORITY", "INVALID_INPUT"
    })
    void 검증_실패는_해당_비즈니스_예외의_상태와_코드로_응답한다(ErrorCode code) throws Exception {
        // given
        authenticate();
        given(orderCommandService.createOrder(USER_ID, REQUEST)).willThrow(new BusinessException(code));

        // when
        // then
        mockMvc.perform(post("/api/orders/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(orderService);
    }

    @Test
    void Redis_접속_실패는_503으로_응답하고_내부_오류_내용을_노출하지_않는다() throws Exception {
        // given
        authenticate();
        given(orderCommandService.createOrder(USER_ID, REQUEST))
                .willThrow(new RedisConnectionFailureException("테스트 내부 연결 오류"));

        // when
        // then
        mockMvc.perform(post("/api/orders/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.SERVICE_UNAVAILABLE.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.SERVICE_UNAVAILABLE.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(orderService);
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(USER_ID), null, AuthorityUtils.NO_AUTHORITIES));
    }

    private static CreateOrderConfirmationResponse response(CreateOrderConfirmationRequest request) {
        return new CreateOrderConfirmationResponse(CONFIRMATION_ID, request.roomId(), request.quoteId(),
                10_500L, "집 · 서울특별시 강남구", LocalDateTime.of(2030, 1, 2, 12, 10));
    }

    private static Stream<String> invalidRequests() {
        return Stream.of(
                "{}",
                "{\"roomId\":null,\"quoteId\":\"QUOTE-114\"}",
                "{\"roomId\":0,\"quoteId\":\"QUOTE-114\"}",
                "{\"roomId\":-1,\"quoteId\":\"QUOTE-114\"}",
                "{\"roomId\":10}",
                "{\"roomId\":10,\"quoteId\":null}",
                "{\"roomId\":10,\"quoteId\":\"\"}",
                "{\"roomId\":10,\"quoteId\":\"   \"}",
                "{\"roomId\":10,\"quoteId\":\"" + "Q".repeat(101) + "\"}",
                "{\"roomId\":\"abc\",\"quoteId\":\"QUOTE-114\"}"
        );
    }
}
