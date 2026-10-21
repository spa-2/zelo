package com.server.zelo.domain.refund.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.entity.OrderItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundItemTest {

    @Mock
    private OrderItem orderItem;

    @BeforeEach
    void setUp() {
        given(orderItem.getQuantity()).willReturn(3);
        given(orderItem.getUnitPrice()).willReturn(2_500L);
    }

    @Test
    void 환불_상품을_만들면_주문_당시_단가를_복사하고_금액은_단가_곱하기_수량이다() {
        // when
        RefundItem refundItem = RefundItem.of(orderItem, 2);

        // then
        assertThat(refundItem.getOrderItem()).isSameAs(orderItem);
        assertThat(refundItem.getQuantity()).isEqualTo(2);
        assertThat(refundItem.getUnitPrice()).isEqualTo(2_500L);
        assertThat(refundItem.getAmount()).isEqualTo(5_000L);
        assertThat(refundItem.getRefund()).isNull();
    }

    @Test
    void 주문_수량만큼_환불할_수_있다() {
        // when
        RefundItem refundItem = RefundItem.of(orderItem, 3);

        // then
        assertThat(refundItem.getAmount()).isEqualTo(7_500L);
    }

    @Test
    void 주문_상품이_없으면_예외가_발생한다() {
        // when
        // then
        assertThatThrownBy(() -> RefundItem.of(null, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, 4})
    void 환불_수량이_1_미만이거나_주문_수량보다_많으면_예외가_발생한다(Integer quantity) {
        // when
        // then
        assertThatThrownBy(() -> RefundItem.of(orderItem, quantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REFUND_QUANTITY));
    }
}