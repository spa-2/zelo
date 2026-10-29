package com.server.zelo.domain.order.controller;

import com.server.zelo.domain.user.entity.UserRole;
import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.delivery.entity.DeliveryStatus;
import com.server.zelo.domain.order.dto.response.GetOrderDetailResponse;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.StockState;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderFacade;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.entity.RefundType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// HTTP 바인딩·인증 리졸버·예외 응답·직렬화를 확인하며 실제 조회 연동은 실행하지 않는다.
@ExtendWith(MockitoExtension.class)
class OrderDetailControllerTest {

    private static final Long USER_ID = 7L;
    private static final Long ORDER_ID = 101L;
    private static final String URL = "/api/orders/101";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2030, 1, 2, 12, 0);

    @Mock private OrderFacade facade;
    @Mock private OrderCommandService command;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(facade,command))
                .setCustomArgumentResolvers(new AuthArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증된_사용자의_ID로_조회하고_상세_응답을_200으로_반환한다() throws Exception {
        // given
        authenticate();
        given(command.getOrderDetail(USER_ID, ORDER_ID)).willReturn(response());

        // when
        // then
        mockMvc.perform(get(URL).param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(101))
                .andExpect(jsonPath("$.data.quoteId").value("QUOTE-DETAIL-169"))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.totalAmount").value(3_000))
                .andExpect(jsonPath("$.data.shippingAddress.address").value("주문 당시 주소"))
                .andExpect(jsonPath("$.data.items[0].orderItemId").value(1_001))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3))
                .andExpect(jsonPath("$.data.items[0].canceledQuantity").value(1))
                .andExpect(jsonPath("$.data.items[0].cancelableQuantity").value(2))
                .andExpect(jsonPath("$.data.payment.portoneId").value("payment-detail-169"))
                .andExpect(jsonPath("$.data.payment.amount").value(3_000))
                .andExpect(jsonPath("$.data.payment.paymentMethodName").value("신한카드"))
                .andExpect(jsonPath("$.data.delivery.deliveryStatus").value("READY"))
                .andExpect(jsonPath("$.data.refund.refundStatus").value("COMPLETED"));
        then(command).should().getOrderDetail(USER_ID, ORDER_ID);
        verifyNoInteractions(facade);
    }

    @Test
    void 결제_배송_환불_기록이_없어도_주문_상세를_200으로_반환한다() throws Exception {
        // given
        authenticate();
        GetOrderDetailResponse detail = response();
        given(command.getOrderDetail(USER_ID, ORDER_ID)).willReturn(new GetOrderDetailResponse(
                detail.orderId(), detail.quoteId(), detail.orderName(), detail.status(),
                detail.totalAmount(), detail.createdAt(), detail.shippingAddress(), detail.items(),
                null, null, null));

        // when
        // then
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(101))
                .andExpect(jsonPath("$.data.totalAmount").value(3_000))
                .andExpect(jsonPath("$.data.shippingAddress.address").value("주문 당시 주소"))
                .andExpect(jsonPath("$.data.items[0].productName").value("구매 당시 사과"))
                .andExpect(jsonPath("$.data.payment").doesNotExist())
                .andExpect(jsonPath("$.data.delivery").doesNotExist())
                .andExpect(jsonPath("$.data.refund").doesNotExist());
        then(command).should().getOrderDetail(USER_ID, ORDER_ID);
        verifyNoInteractions(facade);
    }

    @Test
    void 인증정보가_없으면_401로_응답하고_조회하지_않는다() throws Exception {
        // given
        SecurityContextHolder.clearContext();

        // when
        // then
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        verifyNoInteractions(command, facade);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "1.5", "9223372036854775808"})
    void Long으로_변환할_수_없는_주문_ID는_400으로_응답한다(String orderId) throws Exception {
        // given
        authenticate();

        // when
        // then
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        verifyNoInteractions(command, facade);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void 양수가_아닌_ID의_입력_오류를_400으로_변환한다(Long orderId) throws Exception {
        // given
        authenticate();
        given(command.getOrderDetail(USER_ID, orderId))
                .willThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        // when
        // then
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        then(command).should().getOrderDetail(USER_ID, orderId);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"ORDER_NOT_FOUND", "ORDER_ACCESS_DENIED", "INVALID_INPUT"})
    void 조회_실패는_해당_HTTP_상태와_공통_오류_응답을_반환한다(ErrorCode code) throws Exception {
        // given
        authenticate();
        given(command.getOrderDetail(USER_ID, ORDER_ID)).willThrow(new BusinessException(code));

        // when
        // then
        mockMvc.perform(get(URL))
                .andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(facade);
    }

    private GetOrderDetailResponse response() {
        return new GetOrderDetailResponse(ORDER_ID, "QUOTE-DETAIL-169", "사과 주문", OrderStatus.PAID,
                3_000L, CREATED_AT,
                new GetOrderDetailResponse.ShippingAddressInfo("홍길동", "010-1234-5678", "06236", "주문 당시 주소"),
                List.of(new GetOrderDetailResponse.OrderItemInfo(1_001L, 20L, "구매 당시 사과", "1개",
                        1_000L, 3, 1, 2, 3_000L, StockState.DEDUCTED)),
                new GetOrderDetailResponse.PaymentInfo(201L, "payment-detail-169", PaymentStatus.PAID,
                        3_000L, "신한카드", CREATED_AT),
                new GetOrderDetailResponse.DeliveryInfo(301L, DeliveryStatus.READY, null, null),
                new GetOrderDetailResponse.RefundInfo(401L, RefundStatus.COMPLETED, RefundType.PARTIAL,
                        1_000L, CREATED_AT.plusMinutes(10)));
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                AuthUser.of(USER_ID, UserRole.BASIC), null, AuthorityUtils.NO_AUTHORITIES));
    }
}
