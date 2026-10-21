package com.server.zelo.domain.refund.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.entity.OrderItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class RefundTest {

    private static final Long PAYMENT_ID = 1L;
    private static final String REASON = "단순 변심";
    private static final LocalDateTime REFUNDED_AT = LocalDateTime.of(2026, 10, 21, 12, 0);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void 환불_사유가_비어_있으면_예외가_발생한다(String reason) {
        // when
        // then
        assertThatThrownBy(() -> Refund.of(PAYMENT_ID, RefundType.FULL, reason))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 환불을_생성하면_REQUESTED_상태이고_상품_금액_합계는_0이다() {
        // when
        Refund refund = Refund.of(PAYMENT_ID, RefundType.FULL, REASON);

        // then
        assertThat(refund.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(refund.getRefundType()).isEqualTo(RefundType.FULL);
        assertThat(refund.getReason()).isEqualTo(REASON);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.getItemsAmount()).isZero();
        assertThat(refund.getRefundedAt()).isNull();
        assertThat(refund.getRefundItems()).isEmpty();
    }

    @Test
    void 결제_ID가_없으면_예외가_발생한다() {
        // when
        // then
        assertThatThrownBy(() -> Refund.of(null, RefundType.FULL, REASON))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 환불_유형이_없으면_예외가_발생한다() {
        // when
        // then
        assertThatThrownBy(() -> Refund.of(PAYMENT_ID, null, REASON))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }


    @Test
    void 한_환불에_같은_주문_상품을_두_번_담을_수_없다() {
        // given
        Refund refund = Refund.of(PAYMENT_ID, RefundType.PARTIAL, REASON);
        OrderItem orderItem = orderItem(2, 3_000L);
        refund.addRefundItem(RefundItem.of(orderItem, 1));

        // when
        // then
        assertThatThrownBy(() -> refund.addRefundItem(RefundItem.of(orderItem, 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(refund.getRefundItems()).hasSize(1);
        assertThat(refund.getItemsAmount()).isEqualTo(3_000L);
    }

    @Test
    void 이미_다른_환불에_담긴_상품은_담을_수_없다() {
        // given
        Refund refund = Refund.of(PAYMENT_ID, RefundType.FULL, REASON);
        Refund otherRefund = Refund.of(2L, RefundType.FULL, REASON);
        RefundItem refundItem = RefundItem.of(orderItem(1, 3_000L), 1);
        otherRefund.addRefundItem(refundItem);

        // when
        // then
        assertThatThrownBy(() -> refund.addRefundItem(refundItem))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(refund.getRefundItems()).isEmpty();
        assertThat(refundItem.getRefund()).isSameAs(otherRefund);
    }

    @Test
    void 환불을_완료하면_COMPLETED가_되고_환불_시각을_저장한다() {
        // given
        Refund refund = Refund.of(PAYMENT_ID, RefundType.FULL, REASON);

        // when
        refund.complete(REFUNDED_AT);

        // then
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getRefundedAt()).isEqualTo(REFUNDED_AT);
    }

    @Test
    void 환불이_실패하면_FAILED가_되고_환불_시각은_비어_있다() {
        // given
        Refund refund = Refund.of(PAYMENT_ID, RefundType.FULL, REASON);

        // when
        refund.fail();

        // then
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getRefundedAt()).isNull();
    }

    @Test
    void 완료된_환불은_실패로_바꿀_수_없다() {
        // given
        Refund refund = Refund.of(PAYMENT_ID, RefundType.FULL, REASON);
        refund.complete(REFUNDED_AT);

        // when
        // then
        assertThatThrownBy(refund::fail)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REFUND_STATUS));
    }

    @Test
    void 실패한_환불은_완료로_바꿀_수_없다() {
        // given
        Refund refund = Refund.of(PAYMENT_ID, RefundType.FULL, REASON);
        refund.fail();

        // when
        // then
        assertThatThrownBy(() -> refund.complete(REFUNDED_AT))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REFUND_STATUS));
        assertThat(refund.getRefundedAt()).isNull();
    }

    @Test
    void 요청_중인_환불만_진행_중으로_본다() {
        // given
        Refund requested = Refund.of(PAYMENT_ID, RefundType.FULL, REASON);
        Refund completed = Refund.of(PAYMENT_ID, RefundType.FULL, REASON);
        completed.complete(REFUNDED_AT);
        Refund failed = Refund.of(PAYMENT_ID, RefundType.FULL, REASON);
        failed.fail();

        // when
        // then
        assertThat(requested.isInProgress()).isTrue();
        assertThat(completed.isInProgress()).isFalse();
        assertThat(failed.isInProgress()).isFalse();
    }

    private OrderItem orderItem(Integer quantity, Long unitPrice) {
        OrderItem orderItem = mock(OrderItem.class);
        given(orderItem.getQuantity()).willReturn(quantity);
        given(orderItem.getUnitPrice()).willReturn(unitPrice);
        return orderItem;
    }
}