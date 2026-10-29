package com.server.zelo.domain.order.dto.response;

import com.server.zelo.domain.delivery.entity.Delivery;
import com.server.zelo.domain.delivery.entity.DeliveryStatus;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.order.entity.StockState;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundItem;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.entity.RefundType;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

// 조회 연동과 별개로, 실제 전달받은 엔티티를 응답 값으로 변환하는 계약을 검증한다.
@ExtendWith(MockitoExtension.class)
class GetOrderDetailResponseTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2030, 1, 2, 12, 0);
    private static final LocalDateTime SHIPPED_AT = CREATED_AT.plusDays(1);
    private static final LocalDateTime DELIVERED_AT = SHIPPED_AT.plusDays(1);
    private static final LocalDateTime REFUNDED_AT = CREATED_AT.plusHours(1);

    @Mock private User user;
    @Mock private Product firstProduct;
    @Mock private Product secondProduct;

    @Test
    void 주문과_품목의_구매_스냅샷과_누적_취소_수량을_변환한다() {
        // given
        Fixture fixture = fixture();
        fixture.order().pay();
        fixture.first().deduct();
        fixture.first().cancelQuantity(1);

        // when
        GetOrderDetailResponse response = GetOrderDetailResponse.from(fixture.order(), null, null, null,0L);

        // then
        assertThat(response.orderId()).isEqualTo(101L);
        assertThat(response.quoteId()).isEqualTo("QUOTE-DETAIL-169");
        assertThat(response.orderName()).isEqualTo("사과와 배");
        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.totalAmount()).isEqualTo(7_000L);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
        assertThat(response.shippingAddress()).isEqualTo(new GetOrderDetailResponse.ShippingAddressInfo(
                "홍길동", "010-1234-5678", "06236", "주문 당시 주소"));
        assertThat(response.items()).containsExactly(
                new GetOrderDetailResponse.OrderItemInfo(1_001L, 20L, "견적 당시 사과", "1개",
                        1_000L, 3, 1, 2, 3_000L, StockState.DEDUCTED),
                new GetOrderDetailResponse.OrderItemInfo(1_002L, 10L, "견적 당시 배", "1개",
                        2_000L, 2, 0, 2, 4_000L, StockState.RESERVED));
        assertThat(fixture.first().getQuantity()).isEqualTo(3);
        assertThat(fixture.first().getLineAmount()).isEqualTo(3_000L);
    }

    @Test
    void 연결_기록을_전달받지_않으면_각_정보를_null로_변환한다() {
        // given
        Fixture fixture = fixture();

        // when
        GetOrderDetailResponse response = GetOrderDetailResponse.from(fixture.order(), null, null, null,0L);

        // then
        assertThat(response.payment()).isNull();
        assertThat(response.delivery()).isNull();
        assertThat(response.refund()).isNull();
        assertThat(response.items()).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void READY를_포함한_전달받은_결제_상태를_그대로_반환한다(PaymentStatus status) {
        // given
        Fixture fixture = fixture();
        Payment payment = Payment.of(101L, "payment-detail-169", 7_000L);
        ReflectionTestUtils.setField(payment, "id", 201L);
        ReflectionTestUtils.setField(payment, "status", status);

        // when
        GetOrderDetailResponse response = GetOrderDetailResponse.from(fixture.order(), null, null, null, 0L);

        // then
        assertThat(response.payment()).isEqualTo(new GetOrderDetailResponse.PaymentInfo(
                201L, "payment-detail-169", status, 7_000L, null, null));
    }

    @Test
    void 결제가_완료되면_결제_수단과_금액과_결제_일시를_반환한다() {
        // given
        Fixture fixture = fixture();
        Payment payment = Payment.of(101L, "payment-detail-169", 7_000L);
        ReflectionTestUtils.setField(payment, "id", 201L);
        payment.startConfirm();
        payment.complete(7_000L, CREATED_AT, "신한카드");

        // when
        GetOrderDetailResponse response = GetOrderDetailResponse.from(fixture.order(), null, null, null, 0L);

        // then
        assertThat(response.payment()).isEqualTo(new GetOrderDetailResponse.PaymentInfo(
                201L, "payment-detail-169", PaymentStatus.PAID, 7_000L, "신한카드", CREATED_AT));
    }

    @ParameterizedTest
    @EnumSource(DeliveryStatus.class)
    void 전달받은_배송의_상태와_시각을_변환한다(DeliveryStatus status) {
        // given
        Fixture fixture = fixture();
        Delivery delivery = Delivery.of(101L, "받는 사람", "010-1111-2222", "12345", "배송 주소", null);
        ReflectionTestUtils.setField(delivery, "id", 301L);
        ReflectionTestUtils.setField(delivery, "status", status);
        ReflectionTestUtils.setField(delivery, "shippedAt", SHIPPED_AT);
        ReflectionTestUtils.setField(delivery, "deliveredAt", DELIVERED_AT);

        // when
        GetOrderDetailResponse response = GetOrderDetailResponse.from(fixture.order(), null, null, null, 0L);

        // then
        assertThat(response.delivery()).isEqualTo(new GetOrderDetailResponse.DeliveryInfo(
                301L, status, SHIPPED_AT, DELIVERED_AT));
        assertThat(response.shippingAddress().address()).isEqualTo("주문 당시 주소");
    }

    @ParameterizedTest
    @EnumSource(RefundStatus.class)
    void 전달받은_환불의_상태와_품목_금액을_변환한다(RefundStatus status) {
        // given
        Fixture fixture = fixture();
        Refund refund = Refund.of(201L, RefundType.PARTIAL, "부분 환불");
        refund.addRefundItem(RefundItem.of(fixture.first(), 1));
        ReflectionTestUtils.setField(refund, "id", 401L);
        ReflectionTestUtils.setField(refund, "status", status);
        LocalDateTime completedAt = status == RefundStatus.COMPLETED ? REFUNDED_AT : null;
        ReflectionTestUtils.setField(refund, "refundedAt", completedAt);

        // when
        GetOrderDetailResponse response = GetOrderDetailResponse.from(fixture.order(), null, null, null, 0L);

        // then
        assertThat(response.refund()).isEqualTo(new GetOrderDetailResponse.RefundInfo(
                401L, status, RefundType.PARTIAL, 1_000L, completedAt));
        assertThat(response.totalAmount()).isEqualTo(7_000L);
    }

    @Test
    void 품목이_없는_주문은_빈_품목_목록으로_변환한다() {
        // given
        Order order = createOrder();

        // when
        GetOrderDetailResponse response = GetOrderDetailResponse.from(order, null, null, null, 0L);

        // then
        assertThat(response.items()).isEmpty();
        verifyNoInteractions(firstProduct, secondProduct);
    }

    @Test
    void 전량_취소된_품목도_구매_수량과_금액은_유지해_보여준다() {
        // given
        Fixture fixture = fixture();
        fixture.first().deduct();
        fixture.first().restore();

        // when
        GetOrderDetailResponse response = GetOrderDetailResponse.from(fixture.order(), null, null, null, 0L);

        // then
        GetOrderDetailResponse.OrderItemInfo first = response.items().getFirst();
        assertThat(first.quantity()).isEqualTo(3);
        assertThat(first.canceledQuantity()).isEqualTo(3);
        assertThat(first.cancelableQuantity()).isZero();
        assertThat(first.unitPrice()).isEqualTo(1_000L);
        assertThat(first.lineAmount()).isEqualTo(3_000L);
        assertThat(first.stockState()).isEqualTo(StockState.RESTORED);
    }

    @Test
    void 결제된_주문은_완료된_환불_합계와_환불을_뺀_실결제_금액을_반환한다() {
        // given
        Fixture fixture = fixture();
        fixture.order().pay();

        // when
        GetOrderDetailResponse response = GetOrderDetailResponse.from(fixture.order(), null, null, null, 3_000L);

        // then
        assertThat(response.refundedAmount()).isEqualTo(3_000L);
        assertThat(response.paidAmount()).isEqualTo(4_000L);
    }

    @Test
    void 결제되지_않은_주문은_실결제_금액이_null이다() {
        // given
        Fixture fixture = fixture();

        // when
        GetOrderDetailResponse response = GetOrderDetailResponse.from(fixture.order(), null, null, null, 0L);

        // then
        assertThat(response.refundedAmount()).isZero();
        assertThat(response.paidAmount()).isNull();
    }

    private Fixture fixture() {
        Order order = createOrder();
        OrderItem first = OrderItem.of(order, firstProduct, "견적 당시 사과", "1개", 1_000L, 3, 3_000L);
        OrderItem second = OrderItem.of(order, secondProduct, "견적 당시 배", "1개", 2_000L, 2, 4_000L);
        ReflectionTestUtils.setField(first, "id", 1_001L);
        ReflectionTestUtils.setField(second, "id", 1_002L);
        given(firstProduct.getId()).willReturn(20L);
        given(secondProduct.getId()).willReturn(10L);
        return new Fixture(order, first, second);
    }

    private Order createOrder() {
        Order order = Order.of(user, "QUOTE-DETAIL-169", "사과와 배", ActorType.USER, null, 7_000L,
                ShippingAddress.of("홍길동", "010-1234-5678", "06236", "주문 당시 주소"));
        ReflectionTestUtils.setField(order, "id", 101L);
        ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
        return order;
    }

    private record Fixture(Order order, OrderItem first, OrderItem second) { }
}
