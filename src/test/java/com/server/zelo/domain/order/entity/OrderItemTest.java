package com.server.zelo.domain.order.entity;

import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderItemTest {

    @Mock
    private User user;

    @Mock
    private Product product;

    /**
     * 상품 엔티티의 현재 값에 의존하지 않고 견적에서 받은 구매 정보를 스냅샷으로 보존한다.
     */
    @Test
    void 견적의_상품_정보를_보존하고_양방향_관계를_연결한다() {
        // given
        Order order = Order.of(user, "QUOTE-ITEM-001", "백김치 주문", ActorType.USER, null, 17_800L);

        // when
        OrderItem item = OrderItem.of(order, product, "백김치", "1kg", 8_900L, 2, 17_800L);

        // then
        assertThat(item.getOrder()).isSameAs(order);
        assertThat(item.getProduct()).isSameAs(product);
        assertThat(item.getProductName()).isEqualTo("백김치");
        assertThat(item.getProductUnit()).isEqualTo("1kg");
        assertThat(item.getUnitPrice()).isEqualTo(8_900L);
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getLineAmount()).isEqualTo(17_800L);
        assertThat(order.getOrderItems()).containsExactly(item);
        verifyNoInteractions(product);
    }

    /**
     * 새 주문 상품은 결제 성공에 따른 차감 전까지 선점 상태로 시작한다.
     */
    @Test
    void 주문_상품을_생성하면_선점_상태로_초기화한다() {
        // given
        Order order = Order.of(user, "QUOTE-ITEM-002", "계란 주문", ActorType.USER, null, 4_900L);
        // when
        OrderItem item = OrderItem.of(order, product, "계란", "10구", 4_900L, 1, 4_900L);
        // then
        assertThat(item.getStockState()).isEqualTo(StockState.RESERVED);
    }

    /**
     * 여러 상품을 생성하면 각각 동일한 부모를 가리키고 부모 목록에 한 번씩 연결된다.
     */
    @Test
    void 여러_주문_상품을_생성하면_같은_주문에_각각_한_번씩_연결한다() {
        // given
        Order order = Order.of(user, "QUOTE-ITEM-003", "두 가지 재료", ActorType.USER, null, 13_800L);

        // when
        OrderItem kimchi = OrderItem.of(order, product, "백김치", "1kg", 8_900L, 1, 8_900L);
        OrderItem eggs = OrderItem.of(order, product, "계란", "10구", 4_900L, 1, 4_900L);

        // then
        assertThat(order.getOrderItems()).containsExactly(kimchi, eggs);
        assertThat(kimchi.getOrder()).isSameAs(order);
        assertThat(eggs.getOrder()).isSameAs(order);
    }

    /**
     * Integer 범위를 넘는 금액도 Long 값의 손실 없이 보존한다.
     */
    @Test
    void 정수형_범위를_넘는_단가와_품목_금액을_보존한다() {
        // given
        Long unitPrice = 3_000_000_000L;
        Long lineAmount = 6_000_000_000L;
        Order order = Order.of(user, "QUOTE-ITEM-004", "큰 금액 주문", ActorType.USER, null, lineAmount);

        // when
        OrderItem item = OrderItem.of(order, product, "대량 상품", "1세트", unitPrice, 2, lineAmount);

        // then
        assertThat(item.getUnitPrice()).isEqualTo(unitPrice);
        assertThat(item.getLineAmount()).isEqualTo(lineAmount);
        assertThat(order.getTotalAmount()).isEqualTo(lineAmount);
    }
}
