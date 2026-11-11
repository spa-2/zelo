package com.server.zelo.domain.order.dto.response;

import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GetOrderSummaryResponseTest {

    @Test
    void 결제된_주문은_주문_금액에서_완료된_환불_금액을_뺀_결제_금액을_반환한다() {
        // given
        Order order = order(OrderStatus.PAID);

        // when
        GetOrderSummaryResponse response = GetOrderSummaryResponse.from(order, 1_000L);

        // then
        assertThat(response.totalAmount()).isEqualTo(5_000L);
        assertThat(response.paidAmount()).isEqualTo(4_000L);
    }

    @Test
    void 환불이_없는_결제된_주문은_주문_금액을_결제_금액으로_반환한다() {
        // given
        Order order = order(OrderStatus.PAID);

        // when
        GetOrderSummaryResponse response = GetOrderSummaryResponse.from(order, 0L);

        // then
        assertThat(response.paidAmount()).isEqualTo(5_000L);
    }

    @Test
    void 전액_환불로_취소된_주문은_결제_금액이_0이다() {
        // given
        Order order = order(OrderStatus.CANCELED);

        // when
        GetOrderSummaryResponse response = GetOrderSummaryResponse.from(order, 5_000L);

        // then
        assertThat(response.paidAmount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAYMENT_PENDING", "PAYMENT_FAILED", "CANCELED"})
    void 결제된_적_없는_주문은_결제_금액이_null이다(OrderStatus status) {
        // given
        Order order = order(status);

        // when
        GetOrderSummaryResponse response = GetOrderSummaryResponse.from(order, 0L);

        // then
        assertThat(response.paidAmount()).isNull();
    }

    private Order order(OrderStatus status) {
        Order order = Order.of(mock(User.class), "QUOTE-101", "사과 주문", ActorType.USER, null, 5_000L,
                ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 123"));
        ReflectionTestUtils.setField(order, "id", 101L);
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }
}