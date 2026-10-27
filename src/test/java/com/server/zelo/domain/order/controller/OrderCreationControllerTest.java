package com.server.zelo.domain.order.controller;

import com.server.zelo.domain.user.entity.UserRole;
import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.order.dto.request.CreateOrderRequest;
import com.server.zelo.domain.order.dto.response.CreateOrderResponse;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.model.CreateOrderResult;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderFacade;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 요청 바인딩과 인증 리졸버를 사용하며 JWT 필터와 외부 서버는 실행하지 않는다.
@ExtendWith(MockitoExtension.class)
class OrderCreationControllerTest {

    private static final Long USER_ID = 7L;
    private static final String JSON = "{\"quoteId\":\"QUOTE-161\",\"confirmationId\":\"confirmation-161\"}";
    private static final CreateOrderRequest REQUEST = new CreateOrderRequest("QUOTE-161", "confirmation-161");

    @Mock private OrderFacade facade;
    @Mock private OrderService service;
    @Mock private OrderCommandService command;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(facade, service, command))
                .setCustomArgumentResolvers(new AuthArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 신규는_201_기존은_200으로_주문_응답을_반환한다(boolean created) throws Exception {
        // given
        authenticate();
        given(facade.createOrder(USER_ID, REQUEST)).willReturn(new CreateOrderResult(response("QUOTE-161"), created));

        // when
        // then
        mockMvc.perform(post("/api/orders").param("userId", "999")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().is(created ? 201 : 200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(101))
                .andExpect(jsonPath("$.data.quoteId").value("QUOTE-161"))
                .andExpect(jsonPath("$.data.orderName").value("사과 주문"))
                .andExpect(jsonPath("$.data.actorType").value("USER"))
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.chatRoomId").value(10))
                .andExpect(jsonPath("$.data.totalAmount").value(3_000))
                .andExpect(jsonPath("$.data.portoneId").value("payment-existing-161"))
                .andExpect(jsonPath("$.data.items[0].productId").value(20))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.created").doesNotExist())
                .andExpect(jsonPath("$.data.response").doesNotExist());
        then(facade).should().createOrder(USER_ID, REQUEST);
        verifyNoInteractions(service, command);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void 잘못된_요청은_400으로_응답하고_주문을_생성하지_않는다(String json) throws Exception {
        // given
        authenticate();

        // when
        // then
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        verifyNoInteractions(facade, service, command);
    }

    static Stream<String> invalidRequests() {
        return Stream.of("{}", "{\"confirmationId\":\"ok\"}",
                "{\"quoteId\":null,\"confirmationId\":\"ok\"}",
                "{\"quoteId\":\"\",\"confirmationId\":\"ok\"}",
                "{\"quoteId\":\"   \",\"confirmationId\":\"ok\"}",
                "{\"quoteId\":\"" + "Q".repeat(101) + "\",\"confirmationId\":\"ok\"}",
                "{\"quoteId\":\"ok\"}", "{\"quoteId\":\"ok\",\"confirmationId\":null}",
                "{\"quoteId\":\"ok\",\"confirmationId\":\"\"}",
                "{\"quoteId\":\"ok\",\"confirmationId\":\"   \"}", "{", "");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void 견적_ID_길이_경계값을_허용한다(int length) throws Exception {
        // given
        authenticate();
        String quoteId = "Q".repeat(length);
        CreateOrderRequest request = new CreateOrderRequest(quoteId, "ok");
        given(facade.createOrder(USER_ID, request)).willReturn(new CreateOrderResult(response(quoteId), true));

        // when
        // then
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":\"" + quoteId + "\",\"confirmationId\":\"ok\"}"))
                .andExpect(status().isCreated());
        then(facade).should().createOrder(USER_ID, request);
    }

    @Test
    void 인증정보가_없으면_401로_응답한다() throws Exception {
        // given
        SecurityContextHolder.clearContext();

        // when
        // then
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        verifyNoInteractions(facade, service, command);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"INVALID_INPUT", "NO_AUTHORITY", "QUOTE_EXPIRED",
            "QUOTE_NOT_CURRENT", "PRICE_CHANGED", "PRODUCT_NOT_FOUND", "PENDING_ORDER_EXISTS",
            "INSUFFICIENT_STOCK", "INVALID_QUANTITY", "TIMEOUT_EXCEPTION"})
    void 비즈니스_예외의_HTTP_상태와_코드를_반환한다(ErrorCode code) throws Exception {
        // given
        authenticate();
        given(facade.createOrder(USER_ID, REQUEST)).willThrow(new BusinessException(code));

        // when
        // then
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(service, command);
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                AuthUser.of(USER_ID, UserRole.BASIC), null, AuthorityUtils.NO_AUTHORITIES));
    }

    private CreateOrderResponse response(String quoteId) {
        return new CreateOrderResponse(101L, quoteId, "사과 주문", ActorType.USER, 10L,
                OrderStatus.PAYMENT_PENDING,
                List.of(new CreateOrderResponse.Items(20L, "사과", "1개", 3, 1_000L, 3_000L)),
                3_000L, "payment-existing-161", LocalDateTime.of(2030, 1, 2, 12, 0));
    }
}
