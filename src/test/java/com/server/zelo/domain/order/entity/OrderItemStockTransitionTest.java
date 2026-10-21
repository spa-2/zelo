package com.server.zelo.domain.order.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class OrderItemStockTransitionTest {

    @Mock
    private User user;

    @Mock
    private Product product;

    @ParameterizedTest
    @CsvSource({
            "RESERVED, deduct, DEDUCTED",
            "RESERVED, release, RELEASED",
            "DEDUCTED, restore, RESTORED"
    })
    void 허용한_재고_상태로_변경한다(
            StockState initialState, String operation, StockState expectedState) {
        // given
        OrderItem item = createItem(initialState);

        // when
        execute(item, operation);

        // then
        assertThat(item.getStockState()).isEqualTo(expectedState);
        assertThat(item.isReserved()).isFalse();
        assertThat(item.getQuantity()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({
            "RESERVED, restore",
            "DEDUCTED, deduct",
            "DEDUCTED, release",
            "RELEASED, deduct",
            "RELEASED, release",
            "RELEASED, restore",
            "RESTORED, deduct",
            "RESTORED, release",
            "RESTORED, restore"
    })
    void 허용하지_않은_전이는_예외가_발생하고_기존_상태를_유지한다(
            StockState initialState, String operation) {
        // given
        OrderItem item = createItem(initialState);

        // when
        // then
        assertThatThrownBy(() -> execute(item, operation)).isInstanceOf(BusinessException.class);
        assertThat(item.getStockState()).isEqualTo(initialState);
        assertThat(item.isReserved()).isEqualTo(initialState == StockState.RESERVED);
        assertThat(item.getQuantity()).isEqualTo(3);
    }

    private OrderItem createItem(StockState state) {
        Order order = Order.of(user, "QUOTE-STOCK-TRANSITION", "사과 주문", ActorType.USER, null, 3_000L);
        OrderItem item = OrderItem.of(order, product, "사과", "1개", 1_000L, 3, 3_000L);
        switch (state) {
            case RESERVED -> { }
            case DEDUCTED -> item.deduct();
            case RELEASED -> item.release();
            case RESTORED -> {
                item.deduct();
                item.restore();
            }
        }
        return item;
    }

    private void execute(OrderItem item, String operation) {
        switch (operation) {
            case "deduct" -> item.deduct();
            case "release" -> item.release();
            case "restore" -> item.restore();
            default -> throw new IllegalArgumentException(operation);
        }
    }
}
