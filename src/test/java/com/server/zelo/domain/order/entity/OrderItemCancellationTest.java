package com.server.zelo.domain.order.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class OrderItemCancellationTest {

    @Mock
    private User user;

    @Mock
    private Product product;

    @Test
    void 새_품목의_취소_수량은_0이고_구매_수량만큼_취소할_수_있다() {
        // given
        Order order = createOrder();

        // when
        OrderItem item = createItem(order, 5);

        // then
        assertThat(item.getCanceledQuantity()).isZero();
        assertThat(item.getCancelableQuantity()).isEqualTo(5);
        assertThat(item.isFullyCanceled()).isFalse();
        assertThat(item.getStockState()).isEqualTo(StockState.RESERVED);
    }

    @Test
    void 일부_수량을_취소하면_누적_수량만_증가하고_차감_상태를_유지한다() {
        // given
        Order order = createOrder();
        OrderItem item = createItem(order, 5);
        order.pay();
        item.deduct();

        // when
        item.cancelQuantity(2);

        // then
        assertThat(item.getCanceledQuantity()).isEqualTo(2);
        assertThat(item.getCancelableQuantity()).isEqualTo(3);
        assertThat(item.getStockState()).isEqualTo(StockState.DEDUCTED);
        assertThat(item.isFullyCanceled()).isFalse();
        assertSnapshots(order, item);
    }

    @Test
    void 서로_다른_취소_요청의_수량을_누적한다() {
        // given
        OrderItem item = createDeductedItem();
        item.cancelQuantity(2);

        // when
        item.cancelQuantity(1);

        // then
        assertThat(item.getCanceledQuantity()).isEqualTo(3);
        assertThat(item.getCancelableQuantity()).isEqualTo(2);
        assertThat(item.getStockState()).isEqualTo(StockState.DEDUCTED);
    }

    @Test
    void 남은_수량을_모두_취소하면_전량_복원_상태가_된다() {
        // given
        OrderItem item = createDeductedItem();
        item.cancelQuantity(2);

        // when
        item.cancelQuantity(3);

        // then
        assertThat(item.getCanceledQuantity()).isEqualTo(5);
        assertThat(item.getCancelableQuantity()).isZero();
        assertThat(item.getStockState()).isEqualTo(StockState.RESTORED);
        assertThat(item.isFullyCanceled()).isTrue();
    }

    @Test
    void 부분_취소_후_전체_복원은_남은_수량만_취소_이력에_반영한다() {
        // given
        OrderItem item = createDeductedItem();
        item.cancelQuantity(2);

        // when
        item.restore();

        // then
        assertThat(item.getCanceledQuantity()).isEqualTo(5);
        assertThat(item.getCancelableQuantity()).isZero();
        assertThat(item.getStockState()).isEqualTo(StockState.RESTORED);
        assertThat(item.getQuantity()).isEqualTo(5);
        assertThat(item.getLineAmount()).isEqualTo(5_000L);
    }

    @Test
    void 취소_가능_수량을_검증하는_것만으로는_품목이_변경되지_않는다() {
        // given
        OrderItem item = createDeductedItem();

        // when
        item.validateCancelQuantity(5);

        // then
        assertThat(item.getCanceledQuantity()).isZero();
        assertThat(item.getStockState()).isEqualTo(StockState.DEDUCTED);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, 6})
    void 잘못된_취소_수량은_예외가_발생하고_품목을_변경하지_않는다(Integer quantity) {
        // given
        OrderItem item = createDeductedItem();

        // when
        // then
        assertThatThrownBy(() -> item.cancelQuantity(quantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFUND_QUANTITY));
        assertThat(item.getCanceledQuantity()).isZero();
        assertThat(item.getStockState()).isEqualTo(StockState.DEDUCTED);
    }

    @Test
    void 구매_수량_이하여도_남은_취소_가능_수량을_넘으면_거부한다() {
        // given
        OrderItem item = createDeductedItem();
        item.cancelQuantity(2);

        // when
        // then
        assertThatThrownBy(() -> item.cancelQuantity(4))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFUND_QUANTITY));
        assertThat(item.getCanceledQuantity()).isEqualTo(2);
        assertThat(item.getCancelableQuantity()).isEqualTo(3);
        assertThat(item.getStockState()).isEqualTo(StockState.DEDUCTED);
    }

    @ParameterizedTest
    @EnumSource(value = StockState.class, names = {"RESERVED", "RELEASED", "RESTORED"})
    void 차감_확정_이외의_상태에서는_수량_취소를_거부한다(StockState state) {
        // given
        OrderItem item = createItem(createOrder(), 5);
        switch (state) {
            case RESERVED -> { }
            case RELEASED -> item.release();
            case RESTORED -> {
                item.deduct();
                item.restore();
            }
            default -> throw new IllegalArgumentException(state.name());
        }
        Integer previousCanceledQuantity = item.getCanceledQuantity();

        // when
        // then
        assertThatThrownBy(() -> item.cancelQuantity(1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STOCK_STATUS));
        assertThat(item.getStockState()).isEqualTo(state);
        assertThat(item.getCanceledQuantity()).isEqualTo(previousCanceledQuantity);
    }

    @Test
    void 정수_최댓값의_구매_수량도_범위_안에서_누적_취소한다() {
        // given
        OrderItem item = createItem(createOrder(), Integer.MAX_VALUE);
        item.deduct();
        item.cancelQuantity(Integer.MAX_VALUE - 1);

        // when
        item.cancelQuantity(1);

        // then
        assertThat(item.getCanceledQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(item.getCancelableQuantity()).isZero();
        assertThat(item.getStockState()).isEqualTo(StockState.RESTORED);
    }

    @Test
    void 품목이_없는_주문은_전량_취소된_것으로_판단하지_않는다() {
        // given
        Order order = createOrder();

        // when
        boolean fullyCanceled = order.isFullyCanceled();

        // then
        assertThat(fullyCanceled).isFalse();
    }

    @Test
    void 한_품목이라도_남은_수량이_있으면_주문은_전량_취소가_아니다() {
        // given
        Order order = createOrder();
        OrderItem first = createItem(order, 5);
        OrderItem second = createItem(order, 2);
        first.deduct();
        second.deduct();
        first.cancelQuantity(5);
        second.cancelQuantity(1);

        // when
        boolean fullyCanceled = order.isFullyCanceled();

        // then
        assertThat(fullyCanceled).isFalse();
    }

    @Test
    void 모든_품목이_전량_취소됐는지_확인해도_주문_상태는_자동_변경하지_않는다() {
        // given
        Order order = createOrder();
        OrderItem first = createItem(order, 5);
        OrderItem second = createItem(order, 2);
        order.pay();
        first.deduct();
        second.deduct();
        first.cancelQuantity(5);
        second.cancelQuantity(2);

        // when
        boolean fullyCanceled = order.isFullyCanceled();

        // then
        assertThat(fullyCanceled).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    private Order createOrder() {
        return Order.of(user, "QUOTE-CANCEL-187", "사과 주문", ActorType.USER, null,
                5_000L, ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울 강남구"));
    }

    private OrderItem createItem(Order order, int quantity) {
        return OrderItem.of(order, product, "사과", "1개", 1_000L, quantity, 1_000L * quantity);
    }

    private OrderItem createDeductedItem() {
        OrderItem item = createItem(createOrder(), 5);
        item.deduct();
        return item;
    }

    private void assertSnapshots(Order order, OrderItem item) {
        assertThat(item.getProductName()).isEqualTo("사과");
        assertThat(item.getProductUnit()).isEqualTo("1개");
        assertThat(item.getQuantity()).isEqualTo(5);
        assertThat(item.getUnitPrice()).isEqualTo(1_000L);
        assertThat(item.getLineAmount()).isEqualTo(5_000L);
        assertThat(order.getTotalAmount()).isEqualTo(5_000L);
    }
}
