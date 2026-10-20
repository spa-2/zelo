package com.server.zelo.domain.order.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.ActorType;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class OrderItemOwnershipTest {

    @Mock
    private User user;

    @Mock
    private Product product;

    /**
     * 다른 주문의 상품을 추가해 메모리 관계와 외래 키가 달라지는 결함을 검출한다.
     */
    @Test
    void 다른_주문에_속한_상품을_추가하면_예외가_발생하고_관계가_유지된다() {
        // given
        Order originalOrder = Order.of(user, "QUOTE-OWNER-001", "원래 주문", ActorType.USER, null, 8_900L);
        Order otherOrder = Order.of(user, "QUOTE-OWNER-002", "다른 주문", ActorType.USER, null, 8_900L);
        OrderItem item = OrderItem.of(originalOrder, product, "백김치", "1kg", 8_900L, 1, 8_900L);

        // when
        // then
        assertThatThrownBy(() -> otherOrder.addOrderItem(item))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(item.getOrder()).isSameAs(originalOrder);
        assertThat(originalOrder.getOrderItems()).containsExactly(item);
        assertThat(otherOrder.getOrderItems()).isEmpty();
    }

    /**
     * null 상품은 INVALID_INPUT으로 거부하고 주문 상품 목록을 변경하지 않는다.
     */
    @Test
    void 상품이_null이면_예외가_발생하고_목록은_변경되지_않는다() {
        // given
        Order order = Order.of(user, "QUOTE-OWNER-003", "주문", ActorType.USER, null, 8_900L);

        // when
        // then
        assertThatThrownBy(() -> order.addOrderItem(null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(order.getOrderItems()).isEmpty();
    }
}
