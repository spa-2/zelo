package com.server.zelo.domain.order.entity;

import com.server.zelo.domain.order.ActorType;
import com.server.zelo.domain.order.OrderStatus;
import com.server.zelo.domain.order.StockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class OrderTest {

    @Test
    void ID만으로_주문을_생성한다() {
        // given
        Long userId = 1L;
        Long chatRoomId = 2L;

        // when
        Order order = Order.of(userId, "quote-1", "주문", ActorType.USER, chatRoomId, 3000L);

        // then
        assertThat(order.getUserId()).isEqualTo(userId);
        assertThat(order.getChatRoomId()).isEqualTo(chatRoomId);
        assertThat(order.getQuoteId()).isEqualTo("quote-1");
        assertThat(order.getOrderName()).isEqualTo("주문");
        assertThat(order.getRequesterType()).isEqualTo(ActorType.USER);
        assertThat(order.getTotalAmount()).isEqualTo(3000L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getOrderItems()).isEmpty();
    }

    @Test
    void 채팅방_ID_없이_주문을_생성한다() {
        // given
        Long userId = 1L;

        // when
        Order order = Order.of(userId, "quote-1", "주문", ActorType.USER, null, 3000L);

        // then
        assertThat(order.getUserId()).isEqualTo(userId);
        assertThat(order.getChatRoomId()).isNull();
    }

    @Test
    void 상품_ID로_주문_항목을_생성하고_주문에_등록한다() {
        // given
        Order order = Order.of(1L, "quote-1", "주문", ActorType.USER, null, 3000L);

        // when
        OrderItem orderItem = OrderItem.of(order, 3L, "상품", "개", 1000L, 3, 3000L);

        // then
        assertThat(orderItem.getProductId()).isEqualTo(3L);
        assertThat(orderItem.getProductName()).isEqualTo("상품");
        assertThat(orderItem.getProductUnit()).isEqualTo("개");
        assertThat(orderItem.getUnitPrice()).isEqualTo(1000L);
        assertThat(orderItem.getQuantity()).isEqualTo(3);
        assertThat(orderItem.getLineAmount()).isEqualTo(3000L);
        assertThat(orderItem.getStockState()).isEqualTo(StockState.RESERVED);
        assertThat(orderItem.getStockStateChangedAt()).isNotNull();
        assertThat(orderItem.getOrder()).isSameAs(order);
        assertThat(order.getOrderItems()).containsExactly(orderItem);
    }

    @Test
    void 다른_주문의_항목은_추가할_수_없다() {
        // given
        Order order = Order.of(1L, "quote-1", "주문", ActorType.USER, null, 3000L);
        Order otherOrder = Order.of(1L, "quote-2", "다른 주문", ActorType.USER, null, 3000L);
        OrderItem existingItem = OrderItem.of(order, 3L, "상품", "개", 1000L, 3, 3000L);
        OrderItem otherItem = OrderItem.of(otherOrder, 3L, "상품", "개", 1000L, 3, 3000L);

        // when // then
        assertThatThrownBy(() -> order.addOrderItem(otherItem))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(order.getOrderItems()).containsExactly(existingItem);
        assertThat(otherOrder.getOrderItems()).containsExactly(otherItem);
        assertThat(otherItem.getOrder()).isSameAs(otherOrder);
    }
}
