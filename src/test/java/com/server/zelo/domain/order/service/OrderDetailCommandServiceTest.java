package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.service.ChatRoomService;
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
import com.server.zelo.domain.refund.service.RefundService;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.service.UserAddressService;
import com.server.zelo.domain.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

// TODO(#169): 담당자의 메서드 공개 후 배송·환불 조회 연동 테스트를 추가한다.
// 현재 Command가 임시로 반환하는 배송·환불 null은 테스트의 기대값으로 고정하지 않는다.
@ExtendWith(MockitoExtension.class)
class OrderDetailCommandServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ORDER_ID = 101L;

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
        ReflectionTestUtils.setField(payment, "id", 201L);
        ReflectionTestUtils.setField(payment, "status", status);
        given(orderService.getOwnedOrderWithItems(USER_ID, ORDER_ID)).willReturn(order);
        given(paymentService.findLatestPayment(ORDER_ID)).willReturn(Optional.of(payment));

        // when
        GetOrderDetailResponse response = command.getOrderDetail(USER_ID, ORDER_ID);

        // then
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().productName()).isEqualTo("구매 당시 사과");
        assertThat(response.payment()).isEqualTo(new GetOrderDetailResponse.PaymentInfo(
                201L, "payment-detail-169", status));
        InOrder calls = inOrder(orderService, paymentService);
        calls.verify(orderService).getOwnedOrderWithItems(USER_ID, ORDER_ID);
        calls.verify(paymentService).findLatestPayment(ORDER_ID);
        verifyNoInteractions(inventoryService, productService, quoteService, userAddressService, userService);
    }

    @Test
    void 결제_기록이_없어도_주문과_품목은_반환한다() {
        // given
        Order order = order();
        given(orderService.getOwnedOrderWithItems(USER_ID, ORDER_ID)).willReturn(order);
        given(paymentService.findLatestPayment(ORDER_ID)).willReturn(Optional.empty());

        // when
        GetOrderDetailResponse response = command.getOrderDetail(USER_ID, ORDER_ID);

        // then
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.payment()).isNull();
        assertThat(response.totalAmount()).isEqualTo(3_000L);
        assertThat(response.items()).hasSize(1);
        assertThat(response.shippingAddress().address()).isEqualTo("주문 당시 주소");
        then(paymentService).should().findLatestPayment(ORDER_ID);
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
    }

    @Test
    void 응답_변환을_읽기_전용_트랜잭션_안에서_완료한다() {
        // given
        Order order = order();
        OrderTestTransactionManager manager = new OrderTestTransactionManager();
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(command);
        factory.addAdvice(interceptor);
        OrderCommandService proxy = (OrderCommandService) factory.getProxy();
        given(orderService.getOwnedOrderWithItems(USER_ID, ORDER_ID)).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return order;
        });
        given(paymentService.findLatestPayment(ORDER_ID)).willReturn(Optional.empty());
        given(product.getId()).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return 20L;
        });

        // when
        GetOrderDetailResponse response = proxy.getOrderDetail(USER_ID, ORDER_ID);

        // then
        assertThat(response.items().getFirst().productId()).isEqualTo(20L);
        assertThat(manager.events).containsExactly("begin", "commit");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
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
