package com.server.zelo.domain.order.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class OrderStatusTransitionTest {

    @Mock
    private User user;

    @ParameterizedTest
    @CsvSource({
            "PAYMENT_PENDING, pay, PAID",
            "PAYMENT_PENDING, failPayment, PAYMENT_FAILED",
            "PAYMENT_PENDING, cancel, CANCELED",
            "PAID, cancel, CANCELED"
    })
    void 허용한_주문_상태로_변경한다(
            OrderStatus initialStatus, String operation, OrderStatus expectedStatus) {
        // given
        Order order = createOrder(initialStatus);

        // when
        execute(order, operation);

        // then
        assertThat(order.getStatus()).isEqualTo(expectedStatus);
    }

    @ParameterizedTest
    @CsvSource({
            "PAID, pay",
            "PAID, failPayment",
            "PAYMENT_FAILED, pay",
            "PAYMENT_FAILED, failPayment",
            "PAYMENT_FAILED, cancel",
            "CANCELED, pay",
            "CANCELED, failPayment",
            "CANCELED, cancel"
    })
    void 허용하지_않은_전이는_예외가_발생하고_기존_상태를_유지한다(
            OrderStatus initialStatus, String operation) {
        // given
        Order order = createOrder(initialStatus);

        // when
        // then
        assertThatThrownBy(() -> execute(order, operation))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_STATUS));
        assertThat(order.getStatus()).isEqualTo(initialStatus);
    }

    private Order createOrder(OrderStatus status) {
        Order order = Order.of(user, "QUOTE-TRANSITION", "사과 주문", ActorType.USER, null, 3_000L, shippingAddress());
        switch (status) {
            case PAYMENT_PENDING -> { }
            case PAID -> order.pay();
            case PAYMENT_FAILED -> order.failPayment();
            case CANCELED -> order.cancel();
        }
        return order;
    }

    private void execute(Order order, String operation) {
        switch (operation) {
            case "pay" -> order.pay();
            case "failPayment" -> order.failPayment();
            case "cancel" -> order.cancel();
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private ShippingAddress shippingAddress() {
        return ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 123");
    }
}
