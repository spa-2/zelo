package com.server.zelo.domain.order.service;

import com.server.zelo.common.api.PageResponse;
import com.server.zelo.domain.order.dto.request.SearchOrderCondition;
import com.server.zelo.domain.order.dto.response.GetOrderSummaryResponse;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.refund.service.RefundService;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OrderListCommandServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 22, 14, 30);

    @Mock private User user;
    @Mock private OrderService orderService;
    @Mock private RefundService refundService;
    @InjectMocks private OrderCommandService orderCommandService;

    @Test
    void 페이지의_주문들의_환불_금액을_한_번에_조회해_결제_금액을_붙인다() {
        // given
        SearchOrderCondition condition = new SearchOrderCondition(null, 0, 20);
        Order pending = order(101L, OrderStatus.PAYMENT_PENDING);
        Order paid = order(100L, OrderStatus.PAID);
        Page<Order> orders = new PageImpl<>(List.of(pending, paid), PageRequest.of(0, 20), 2);
        given(orderService.getMyOrders(USER_ID, condition)).willReturn(orders);
        given(refundService.findRefundedAmounts(List.of(101L, 100L))).willReturn(Map.of(100L, 1_000L));

        // when
        PageResponse<GetOrderSummaryResponse> result = orderCommandService.getMyOrders(USER_ID, condition);

        // then
        assertThat(result.content()).containsExactly(
                new GetOrderSummaryResponse(101L, OrderStatus.PAYMENT_PENDING, "주문", 5_000L, null, CREATED_AT),
                new GetOrderSummaryResponse(100L, OrderStatus.PAID, "주문", 5_000L, 4_000L, CREATED_AT)
        );
        assertThat(result.totalElements()).isEqualTo(2);
    }

    private Order order(Long id, OrderStatus status) {
        Order order = Order.of(user, "QUOTE-" + id, "주문", ActorType.USER, null, 5_000L,
                ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 123"));
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
        return order;
    }
}