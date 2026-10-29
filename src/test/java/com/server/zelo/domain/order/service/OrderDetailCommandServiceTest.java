package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.service.ChatRoomService;
import com.server.zelo.domain.delivery.entity.Delivery;
import com.server.zelo.domain.delivery.entity.DeliveryStatus;
import com.server.zelo.domain.delivery.service.DeliveryService;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.order.dto.response.GetOrderDetailResponse;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.service.PaymentService;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.service.QuoteService;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundItem;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.entity.RefundType;
import com.server.zelo.domain.refund.service.RefundService;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.service.UserAddressService;
import com.server.zelo.domain.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderDetailCommandServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ORDER_ID = 101L;
    // 주문 ID를 환불 조회에 잘못 전달하면 테스트가 실패하도록 서로 다른 값을 사용한다.
    private static final Long PAYMENT_ID = 201L;
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2030, 1, 2, 12, 0);
    private static final LocalDateTime REFUNDED_AT = PAID_AT.plusHours(1);
    private static final LocalDateTime SHIPPED_AT = PAID_AT.plusDays(1);
    private static final LocalDateTime DELIVERED_AT = SHIPPED_AT.plusDays(1);

    @Mock private UserService userService;
    @Mock private OrderService orderService;
    @Mock private QuoteService quoteService;
    @Mock private RefundService refundService;
    @Mock private ProductService productService;
    @Mock private PaymentService paymentService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private DeliveryService deliveryService;
    @Mock private InventoryService inventoryService;
    @Mock private UserAddressService userAddressService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private User user;
    @Mock private Product product;
    @InjectMocks private OrderCommandService command;

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void 소유권을_먼저_확인하고_READY를_포함한_최신_결제_정보를_반환한다(PaymentStatus status) {
        // given
        Order order = order();
        Payment payment = Payment.of(ORDER_ID, "payment-detail-169", 3_000L);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        ReflectionTestUtils.setField(payment, "status", status);
        given(orderService.getOwnedOrderWithItems(USER_ID, ORDER_ID)).willReturn(order);
        given(paymentService.findLatestPayment(ORDER_ID)).willReturn(Optional.of(payment));
        given(deliveryService.findDelivery(ORDER_ID)).willReturn(Optional.empty());
        given(refundService.findLatestRefund(PAYMENT_ID)).willReturn(Optional.empty());

        // when
        GetOrderDetailResponse response = command.getOrderDetail(USER_ID, ORDER_ID);

        // then
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().productName()).isEqualTo("구매 당시 사과");
        assertThat(response.payment()).isEqualTo(new GetOrderDetailResponse.PaymentInfo(
                PAYMENT_ID, "payment-detail-169", status, 3_000L, null, null));
        assertThat(response.delivery()).isNull();
        assertThat(response.refund()).isNull();
        InOrder calls = inOrder(orderService, paymentService);
        calls.verify(orderService).getOwnedOrderWithItems(USER_ID, ORDER_ID);
        calls.verify(paymentService).findLatestPayment(ORDER_ID);
        then(deliveryService).should().findDelivery(ORDER_ID);
        then(refundService).should().findLatestRefund(PAYMENT_ID);
        then(refundService).should(never()).findLatestRefund(ORDER_ID);
        verifyNoInteractions(inventoryService, productService, quoteService, userAddressService, userService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 결제_기록이_없으면_환불을_조회하지_않고_주문과_배송은_반환한다(boolean hasDelivery) {
        // given
        Order order = order();
        given(orderService.getOwnedOrderWithItems(USER_ID, ORDER_ID)).willReturn(order);
        given(paymentService.findLatestPayment(ORDER_ID)).willReturn(Optional.empty());
        given(deliveryService.findDelivery(ORDER_ID))
                .willReturn(hasDelivery ? Optional.of(delivery()) : Optional.empty());

        // when
        GetOrderDetailResponse response = command.getOrderDetail(USER_ID, ORDER_ID);

        // then
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.payment()).isNull();
        assertThat(response.refund()).isNull();
        assertThat(response.delivery()).isEqualTo(hasDelivery ? deliveryInfo() : null);
        assertThat(response.totalAmount()).isEqualTo(3_000L);
        assertThat(response.items()).hasSize(1);
        assertThat(response.shippingAddress().address()).isEqualTo("주문 당시 주소");
        then(paymentService).should().findLatestPayment(ORDER_ID);
        then(deliveryService).should().findDelivery(ORDER_ID);
        verifyNoInteractions(refundService);
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void 배송과_최신_환불의_존재_여부에_따라_상세_정보를_반환한다(
            boolean hasDelivery, boolean hasRefund
    ) {
        // given
        Order order = order();
        order.pay();
        order.getOrderItems().getFirst().deduct();
        Payment payment = paidPayment();
        Refund refund = refund(order);
        given(orderService.getOwnedOrderWithItems(USER_ID, ORDER_ID)).willReturn(order);
        given(paymentService.findLatestPayment(ORDER_ID)).willReturn(Optional.of(payment));
        given(deliveryService.findDelivery(ORDER_ID))
                .willReturn(hasDelivery ? Optional.of(delivery()) : Optional.empty());
        given(refundService.findLatestRefund(PAYMENT_ID))
                .willReturn(hasRefund ? Optional.of(refund) : Optional.empty());
        given(refundService.calculateRefundedAmount(PAYMENT_ID)).willReturn(hasRefund ? 1_000L : 0L);

        // when
        GetOrderDetailResponse response = command.getOrderDetail(USER_ID, ORDER_ID);

        // then
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.payment()).isEqualTo(new GetOrderDetailResponse.PaymentInfo(
                PAYMENT_ID, "payment-detail-169", PaymentStatus.PAID, 3_000L, "TEST_CARD", PAID_AT));
        assertThat(response.delivery()).isEqualTo(hasDelivery ? deliveryInfo() : null);
        assertThat(response.refund()).isEqualTo(hasRefund ? refundInfo() : null);
        assertThat(response.items().getFirst().productName()).isEqualTo("구매 당시 사과");
        assertThat(response.totalAmount()).isEqualTo(3_000L);
        assertThat(response.shippingAddress().address()).isEqualTo("주문 당시 주소");
        assertThat(response.refundedAmount()).isEqualTo(hasRefund ? 1_000L : 0L);
        then(deliveryService).should().findDelivery(ORDER_ID);
        then(refundService).should().findLatestRefund(PAYMENT_ID);
        then(refundService).should(never()).findLatestRefund(ORDER_ID);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"INVALID_INPUT", "ORDER_NOT_FOUND", "ORDER_ACCESS_DENIED"})
    void 주문_검증에_실패하면_결제와_다른_도메인_정보를_조회하지_않는다(ErrorCode code) {
        // given
        given(orderService.getOwnedOrderWithItems(USER_ID, ORDER_ID))
                .willThrow(new BusinessException(code));

        // when
        // then
        assertThatThrownBy(() -> command.getOrderDetail(USER_ID, ORDER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        verifyNoInteractions(paymentService, deliveryService, refundService,
                inventoryService, productService, quoteService, userAddressService, userService);
    }

    @Test
    void 결제_조회_실패를_결제_없음으로_바꾸지_않고_전달한다() {
        // given
        Order order = orderWithoutItems();
        IllegalStateException failure = new IllegalStateException("결제 저장소 조회 실패");
        given(orderService.getOwnedOrderWithItems(USER_ID, ORDER_ID)).willReturn(order);
        given(paymentService.findLatestPayment(ORDER_ID)).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> command.getOrderDetail(USER_ID, ORDER_ID)).isSameAs(failure);
        verifyNoInteractions(deliveryService, refundService);
    }

    @Test
    void 배송_조회_실패를_배송_없음으로_바꾸지_않고_전달한다() {
        // given
        Order order = orderWithoutItems();
        IllegalStateException failure = new IllegalStateException("배송 저장소 조회 실패");
        given(orderService.getOwnedOrderWithItems(USER_ID, ORDER_ID)).willReturn(order);
        given(paymentService.findLatestPayment(ORDER_ID)).willReturn(Optional.of(paidPayment()));
        given(deliveryService.findDelivery(ORDER_ID)).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> command.getOrderDetail(USER_ID, ORDER_ID)).isSameAs(failure);
        verifyNoInteractions(refundService);
    }

    @Test
    void 환불_조회_실패를_환불_없음으로_바꾸지_않고_전달한다() {
        // given
        Order order = orderWithoutItems();
        IllegalStateException failure = new IllegalStateException("환불 저장소 조회 실패");
        given(orderService.getOwnedOrderWithItems(USER_ID, ORDER_ID)).willReturn(order);
        given(paymentService.findLatestPayment(ORDER_ID)).willReturn(Optional.of(paidPayment()));
        given(deliveryService.findDelivery(ORDER_ID)).willReturn(Optional.empty());
        given(refundService.findLatestRefund(PAYMENT_ID)).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> command.getOrderDetail(USER_ID, ORDER_ID)).isSameAs(failure);
        then(refundService).should().findLatestRefund(PAYMENT_ID);
    }

    @Test
    void 결제_배송_환불_조회와_응답_변환을_읽기_전용_트랜잭션_안에서_완료한다() {
        // given
        Order order = order();
        Payment payment = paidPayment();
        Delivery delivery = delivery();
        Refund refund = refund(order);
        OrderTestTransactionManager manager = new OrderTestTransactionManager();
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(command);
        factory.addAdvice(interceptor);
        OrderCommandService proxy = (OrderCommandService) factory.getProxy();
        given(orderService.getOwnedOrderWithItems(USER_ID, ORDER_ID)).willAnswer(invocation -> {
            assertReadOnlyTransaction();
            return order;
        });
        given(paymentService.findLatestPayment(ORDER_ID)).willAnswer(invocation -> {
            assertReadOnlyTransaction();
            return Optional.of(payment);
        });
        given(deliveryService.findDelivery(ORDER_ID)).willAnswer(invocation -> {
            assertReadOnlyTransaction();
            return Optional.of(delivery);
        });
        given(refundService.findLatestRefund(PAYMENT_ID)).willAnswer(invocation -> {
            assertReadOnlyTransaction();
            return Optional.of(refund);
        });
        given(product.getId()).willAnswer(invocation -> {
            assertReadOnlyTransaction();
            return 20L;
        });

        // when
        GetOrderDetailResponse response = proxy.getOrderDetail(USER_ID, ORDER_ID);

        // then
        assertThat(response.items().getFirst().productId()).isEqualTo(20L);
        assertThat(response.payment().paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(response.delivery()).isEqualTo(deliveryInfo());
        assertThat(response.refund()).isEqualTo(refundInfo());
        assertThat(manager.events).containsExactly("begin", "commit");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private void assertReadOnlyTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
    }

    private Payment paidPayment() {
        Payment payment = Payment.of(ORDER_ID, "payment-detail-169", 3_000L);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        payment.startConfirm();
        payment.complete(3_000L, PAID_AT, "TEST_CARD");
        return payment;
    }

    private Delivery delivery() {
        Delivery delivery = Delivery.of(ORDER_ID, "받는 사람", "010-1111-2222", "12345", "배송 주소", null);
        ReflectionTestUtils.setField(delivery, "id", 301L);
        delivery.ship(SHIPPED_AT);
        delivery.complete(DELIVERED_AT);
        return delivery;
    }

    private Refund refund(Order order) {
        Refund refund = Refund.of(PAYMENT_ID, RefundType.PARTIAL, "부분 환불");
        refund.addRefundItem(RefundItem.of(order.getOrderItems().getFirst(), 1));
        ReflectionTestUtils.setField(refund, "id", 401L);
        refund.complete(REFUNDED_AT);
        return refund;
    }

    private GetOrderDetailResponse.DeliveryInfo deliveryInfo() {
        return new GetOrderDetailResponse.DeliveryInfo(
                301L, DeliveryStatus.DELIVERED, SHIPPED_AT, DELIVERED_AT);
    }

    private GetOrderDetailResponse.RefundInfo refundInfo() {
        return new GetOrderDetailResponse.RefundInfo(
                401L, RefundStatus.COMPLETED, RefundType.PARTIAL, 1_000L, REFUNDED_AT);
    }

    private Order order() {
        Order order = orderWithoutItems();
        OrderItem item = OrderItem.of(order, product, "구매 당시 사과", "1개", 1_000L, 3, 3_000L);
        ReflectionTestUtils.setField(item, "id", 1_001L);
        // 조회용 DTO는 상품의 식별자만 사용하고 현재 이름이나 가격은 읽지 않는다.
        given(product.getId()).willReturn(20L);
        return order;
    }

    private Order orderWithoutItems() {
        Order order = Order.of(user, "QUOTE-DETAIL-169", "사과 주문", ActorType.USER, null, 3_000L,
                ShippingAddress.of("홍길동", "010-1234-5678", "06236", "주문 당시 주소"));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }
}
