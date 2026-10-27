package com.server.zelo.domain.order.controller;

import com.server.zelo.domain.user.entity.UserRole;
import com.server.zelo.common.api.PageResponse;
import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.order.dto.request.SearchOrderCondition;
import com.server.zelo.domain.order.dto.response.GetOrderSummaryResponse;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.order.service.OrderFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 바인딩·검증·인증 리졸버·공통 예외 처리를 사용하며 JWT 필터와 DB는 실행하지 않는다.
@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    private static final Long USER_ID = 7L;

    @Mock
    private OrderService orderService;

    @Mock
    private OrderCommandService orderCommandService;

    @Captor
    private ArgumentCaptor<SearchOrderCondition> conditionCaptor;

    @Mock
    private OrderFacade orderFacade;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderFacade, orderService, orderCommandService))
                .setCustomArgumentResolvers(new AuthArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 조회_조건을_생략하면_기본_페이지로_조회하고_주문_목록_응답을_반환한다() throws Exception {
        // given
        authenticate();
        GetOrderSummaryResponse order = new GetOrderSummaryResponse(
                101L, OrderStatus.PAID, "사과 주문", 3_000L, LocalDateTime.of(2026, 10, 22, 14, 30));
        given(orderService.getMyOrders(eq(USER_ID), any(SearchOrderCondition.class)))
                .willReturn(new PageResponse<>(List.of(order), 0, 20, 1, 1));

        // when
        // then
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].orderId").value(101))
                .andExpect(jsonPath("$.data.content[0].status").value("PAID"))
                .andExpect(jsonPath("$.data.content[0].orderName").value("사과 주문"))
                .andExpect(jsonPath("$.data.content[0].totalAmount").value(3_000))
                .andExpect(jsonPath("$.data.content[0].createdAt").exists())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
        assertRequest(0, 20, null);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void 정의된_상태는_검색조건으로_전달하고_빈_목록도_200으로_반환한다(OrderStatus orderStatus) throws Exception {
        // given
        authenticate();
        given(orderService.getMyOrders(eq(USER_ID), any(SearchOrderCondition.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        // when
        // then
        mockMvc.perform(get("/api/orders").param("status", orderStatus.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
        assertRequest(0, 20, orderStatus);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "0, 100", "2, 5"})
    void 유효한_페이지와_크기는_그대로_전달한다(int page, int size) throws Exception {
        // given
        authenticate();
        given(orderService.getMyOrders(eq(USER_ID), any(SearchOrderCondition.class)))
                .willReturn(new PageResponse<>(List.of(), page, size, 0, 0));

        // when
        // then
        mockMvc.perform(get("/api/orders")
                        .param("page", Integer.toString(page))
                        .param("size", Integer.toString(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(page))
                .andExpect(jsonPath("$.data.size").value(size));
        assertRequest(page, size, null);
    }

    @ParameterizedTest
    @CsvSource({"page, 2, 2, 20", "size, 5, 0, 5"})
    void 페이지나_크기_중_하나만_보내면_생략한_값에만_기본값을_적용한다(
            String name, String value, int expectedPage, int expectedSize) throws Exception {
        // given
        authenticate();
        given(orderService.getMyOrders(eq(USER_ID), any(SearchOrderCondition.class)))
                .willReturn(new PageResponse<>(List.of(), expectedPage, expectedSize, 0, 0));

        // when
        // then
        mockMvc.perform(get("/api/orders").param(name, value))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(expectedPage))
                .andExpect(jsonPath("$.data.size").value(expectedSize));
        assertRequest(expectedPage, expectedSize, null);
    }

    @ParameterizedTest
    @CsvSource({"page, -1", "size, 0", "size, -1", "size, 101"})
    void 페이지나_크기가_범위를_벗어나면_400과_INVALID_INPUT을_반환한다(String name, String value) throws Exception {
        // given
        authenticate();

        // when
        // then
        mockMvc.perform(get("/api/orders").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @CsvSource({"page, abc", "size, abc", "page, 2147483648", "size, 2147483648"})
    void 페이지나_크기를_Integer로_변환할_수_없으면_400을_반환한다(String name, String value) throws Exception {
        // given
        authenticate();

        // when
        // then
        mockMvc.perform(get("/api/orders").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_STATUS", "paid"})
    void 정의되지_않은_상태는_400과_INVALID_INPUT을_반환한다(String invalidStatus) throws Exception {
        // given
        authenticate();

        // when
        // then
        mockMvc.perform(get("/api/orders").param("status", invalidStatus))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(orderService);
    }

    @Test
    void 요청에_다른_사용자_ID를_넣어도_인증된_사용자_ID로_조회한다() throws Exception {
        // given
        authenticate();
        given(orderService.getMyOrders(eq(USER_ID), any(SearchOrderCondition.class)))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        // when
        // then
        mockMvc.perform(get("/api/orders").param("userId", "999"))
                .andExpect(status().isOk());
        assertRequest(0, 20, null);
    }

    @Test
    void 인증_정보가_없으면_401과_UNAUTHORIZED를_반환한다() throws Exception {
        // given
        SecurityContextHolder.clearContext();

        // when
        // then
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        verifyNoInteractions(orderService);
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(USER_ID, UserRole.BASIC), null, AuthorityUtils.NO_AUTHORITIES));
    }

    private void assertRequest(int page, int size, OrderStatus status) {
        then(orderService).should().getMyOrders(eq(USER_ID), conditionCaptor.capture());
        then(orderService).shouldHaveNoMoreInteractions();
        assertThat(conditionCaptor.getValue().page()).isEqualTo(page);
        assertThat(conditionCaptor.getValue().size()).isEqualTo(size);
        assertThat(conditionCaptor.getValue().status()).isEqualTo(status);
    }
}
