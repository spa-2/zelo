package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.service.ChatRoomService;
import com.server.zelo.domain.delivery.service.DeliveryService;
import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.order.entity.StockState;
import com.server.zelo.domain.order.repository.OrderConfirmationRepository;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import com.server.zelo.domain.payment.service.PaymentService;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.service.QuoteService;
import com.server.zelo.domain.refund.service.RefundService;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.service.UserAddressService;
import com.server.zelo.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderExpirationCommandServiceTest {

    private static final Long ORDER_ID = 101L;
    private static final String PORTONE_ID = "payment-expiration-214";
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2030, 1, 2, 12, 0);
    private static final List<PaymentStatus> BLOCKED =
            List.of(PaymentStatus.CONFIRM_PENDING, PaymentStatus.PAID);

    @Mock private User user;
    @Mock private Product firstProduct;
    @Mock private Product secondProduct;
    @Mock private UserService userService;
    @Mock private QuoteService quoteService;
    @Mock private RefundService refundService;
    @Mock private ProductService productService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private DeliveryService deliveryService;
    @Mock private UserAddressService userAddressService;
    @Mock private OrderRepository orderRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private OrderConfirmationRepository confirmationRepository;

    private OrderCommandService command;
    private OrderTestTransactionManager manager;

    @BeforeEach
    void setUp() {
        manager = new OrderTestTransactionManager();
        OrderCommandService target = new OrderCommandService(
                userService, new OrderService(orderRepository, confirmationRepository),
                quoteService, refundService, productService, new PaymentService(paymentRepository),
                chatRoomService, deliveryService, new InventoryService(inventoryRepository),
                userAddressService, new TransactionTemplate(manager));
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        command = (OrderCommandService) factory.getProxy();
    }

    @Test
    void 주문을_잠근_뒤_만료를_재확인하고_본인_품목의_선점만_해제한다() {
        // given
        Fixture fixture = fixture();
        stubOrder(fixture);
        stubProducts();
        given(orderRepository.existsExpiredOrder(ORDER_ID, OrderStatus.PAYMENT_PENDING, CUTOFF, BLOCKED))
                .willAnswer(invocation -> {
                    then(orderRepository).should(atLeastOnce()).findForUpdateById(ORDER_ID);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
                    return true;
                });
        given(inventoryRepository.findInventoryByProduct_Id(10L)).willReturn(Optional.of(fixture.first()));
        given(inventoryRepository.findInventoryByProduct_Id(20L)).willReturn(Optional.of(fixture.second()));

        // when
        boolean canceled = command.cancelExpiredOrder(ORDER_ID, CUTOFF);

        // then
        assertThat(canceled).isTrue();
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsOnly(StockState.RELEASED);
        assertQuantities(fixture.first(), 10, 1, 9);
        assertQuantities(fixture.second(), 20, 1, 19);
        InOrder inventoryCalls = inOrder(inventoryRepository);
        inventoryCalls.verify(inventoryRepository).findInventoryByProduct_Id(10L);
        inventoryCalls.verify(inventoryRepository).findInventoryByProduct_Id(20L);
        assertThat(manager.events).containsExactly("begin", "commit");
    }

    @Test
    void 잠금_후_만료_조건을_만족하지_않으면_상태와_재고를_변경하지_않는다() {
        // given
        Fixture fixture = fixture();
        stubOrder(fixture);
        given(orderRepository.existsExpiredOrder(ORDER_ID, OrderStatus.PAYMENT_PENDING, CUTOFF, BLOCKED))
                .willReturn(false);

        // when
        boolean canceled = command.cancelExpiredOrder(ORDER_ID, CUTOFF);

        // then
        assertThat(canceled).isFalse();
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsOnly(StockState.RESERVED);
        assertQuantities(fixture.first(), 10, 4, 6);
        assertQuantities(fixture.second(), 20, 3, 17);
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void 같은_주문을_다시_처리해도_선점_재고는_한_번만_해제한다() {
        // given
        Fixture fixture = fixture();
        stubOrder(fixture);
        stubProducts();
        given(orderRepository.existsExpiredOrder(ORDER_ID, OrderStatus.PAYMENT_PENDING, CUTOFF, BLOCKED))
                .willReturn(true, false);
        given(inventoryRepository.findInventoryByProduct_Id(10L)).willReturn(Optional.of(fixture.first()));
        given(inventoryRepository.findInventoryByProduct_Id(20L)).willReturn(Optional.of(fixture.second()));

        // when
        boolean first = command.cancelExpiredOrder(ORDER_ID, CUTOFF);
        boolean second = command.cancelExpiredOrder(ORDER_ID, CUTOFF);

        // then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertQuantities(fixture.first(), 10, 1, 9);
        assertQuantities(fixture.second(), 20, 1, 19);
        then(inventoryRepository).should(times(1)).findInventoryByProduct_Id(10L);
        then(inventoryRepository).should(times(1)).findInventoryByProduct_Id(20L);
        assertThat(manager.events).containsExactly("begin", "commit", "begin", "commit");
    }

    @Test
    void 주문이_없으면_예외를_전달하고_트랜잭션을_롤백한다() {
        // given
        given(orderRepository.findForUpdateById(ORDER_ID)).willReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> command.cancelExpiredOrder(ORDER_ID, CUTOFF))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        verifyNoInteractions(inventoryRepository);
        assertThat(manager.events).containsExactly("begin", "rollback");
    }

    @Test
    void 재고_해제_실패는_스케줄러까지_전달하고_트랜잭션을_롤백한다() {
        // given
        Fixture fixture = fixture();
        stubOrder(fixture);
        stubProducts();
        given(orderRepository.existsExpiredOrder(ORDER_ID, OrderStatus.PAYMENT_PENDING, CUTOFF, BLOCKED))
                .willReturn(true);
        BusinessException failure = new BusinessException(ErrorCode.INVALID_STOCK);
        given(inventoryRepository.findInventoryByProduct_Id(10L)).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> command.cancelExpiredOrder(ORDER_ID, CUTOFF)).isSameAs(failure);
        assertThat(manager.events).containsExactly("begin", "rollback");
        // 실제 DB의 데이터 원복은 Repository 통합 테스트에서 별도로 검증한다.
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 락_키_조회는_읽기_전용_트랜잭션에서_결제번호만_읽는다(boolean hasPayment) {
        // given
        Payment payment = Payment.of(ORDER_ID, PORTONE_ID, 7_000L);
        given(paymentRepository.findTopByOrderIdOrderByIdDesc(ORDER_ID)).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return hasPayment ? Optional.of(payment) : Optional.empty();
        });

        // when
        Optional<String> result = command.findPortOneIdForExpiration(ORDER_ID);

        // then
        assertThat(result).isEqualTo(hasPayment ? Optional.of(PORTONE_ID) : Optional.empty());
        verifyNoInteractions(orderRepository, inventoryRepository);
        assertThat(manager.events).containsExactly("begin", "commit");
    }

    @Test
    void 결제번호_조회_실패를_결제_없음으로_숨기지_않는다() {
        // given
        IllegalStateException failure = new IllegalStateException("결제 저장소 오류");
        given(paymentRepository.findTopByOrderIdOrderByIdDesc(ORDER_ID)).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> command.findPortOneIdForExpiration(ORDER_ID)).isSameAs(failure);
        verifyNoInteractions(orderRepository, inventoryRepository);
    }

    private Fixture fixture() {
        Order order = Order.of(user, "QUOTE-214", "사과와 배", ActorType.USER, null, 7_000L,
                ShippingAddress.of("홍길동", "01012345678", "12345", "주문 당시 주소"));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order, "createdAt", CUTOFF.minusMinutes(1));
        OrderItem.of(order, secondProduct, "배", "1개", 2_000L, 2, 4_000L);
        OrderItem.of(order, firstProduct, "사과", "1개", 1_000L, 3, 3_000L);
        Inventory first = Inventory.of(firstProduct, 10);
        Inventory second = Inventory.of(secondProduct, 20);
        first.reserve(4);
        second.reserve(3);
        return new Fixture(order, first, second);
    }

    private void stubOrder(Fixture fixture) {
        given(orderRepository.findForUpdateById(ORDER_ID)).willReturn(Optional.of(fixture.order()));
        given(orderRepository.findWithOrderItemsById(ORDER_ID)).willReturn(Optional.of(fixture.order()));
    }

    private void stubProducts() {
        given(firstProduct.getId()).willReturn(10L);
        given(secondProduct.getId()).willReturn(20L);
    }

    private void assertQuantities(Inventory inventory, int stock, int reserved, int available) {
        assertThat(inventory.getStockQuantity()).isEqualTo(stock);
        assertThat(inventory.getReservedQuantity()).isEqualTo(reserved);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(available);
    }

    private record Fixture(Order order, Inventory first, Inventory second) { }
}
