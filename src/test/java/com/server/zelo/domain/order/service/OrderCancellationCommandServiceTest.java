package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.service.ChatRoomService;
import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.order.dto.response.CancelOrderResponse;
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
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.service.UserAddressService;
import com.server.zelo.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderCancellationCommandServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ORDER_ID = 101L;
    private static final String PORTONE_ID = "payment-cancel-170";

    @Mock private User user;
    @Mock private Product firstProduct;
    @Mock private Product secondProduct;
    @Mock private UserService userService;
    @Mock private QuoteService quoteService;
    @Mock private ProductService productService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private UserAddressService userAddressService;
    @Mock private OrderRepository orderRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private OrderConfirmationRepository confirmationRepository;

    private OrderCommandService command;
    private OrderTestTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionManager = new OrderTestTransactionManager();
        transactionTemplate = new TransactionTemplate(transactionManager);
        // 상태와 수량 변경은 실제 Service·엔티티로 확인하고 저장소만 Mock으로 둔다.
        command = new OrderCommandService(userService,
                new OrderService(orderRepository, confirmationRepository), quoteService, productService,
                new PaymentService(paymentRepository), chatRoomService,
                new InventoryService(inventoryRepository), userAddressService, transactionTemplate);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 결제_전_주문을_취소하면_본인_품목의_선점만_해제한다(boolean hasPayment) {
        // given
        Fixture fixture = fixture();
        stubOrder(fixture, false);
        stubInventories(fixture);
        Payment payment = payment(PaymentStatus.READY);
        given(paymentRepository.findTopByOrderIdOrderByIdDesc(ORDER_ID))
                .willReturn(hasPayment ? Optional.of(payment) : Optional.empty());

        // when
        CancelOrderResponse result = cancel();

        // then
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.orderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsOnly(StockState.RELEASED);
        assertQuantities(fixture.firstInventory(), 10, 1, 9);
        assertQuantities(fixture.secondInventory(), 20, 1, 19);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        InOrder inventoryCalls = inOrder(inventoryRepository);
        inventoryCalls.verify(inventoryRepository).findInventoryByProduct_Id(10L);
        inventoryCalls.verify(inventoryRepository).findInventoryByProduct_Id(20L);
        then(paymentRepository).should(never()).save(any());
        assertThat(transactionManager.events).containsExactly("begin", "commit");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 사전_조회는_결제번호만_반환하고_주문_행을_잠그지_않는다(boolean hasPayment) {
        // given
        Fixture fixture = fixture();
        stubOrder(fixture, true);
        given(paymentRepository.findTopByOrderIdOrderByIdDesc(ORDER_ID))
                .willReturn(hasPayment ? Optional.of(payment(PaymentStatus.READY)) : Optional.empty());

        // when
        Optional<String> result = command.findPaymentIdForCancellation(USER_ID, ORDER_ID);

        // then
        assertThat(result).isEqualTo(hasPayment ? Optional.of(PORTONE_ID) : Optional.empty());
        then(orderRepository).should(never()).findForUpdateById(any());
        assertUnchanged(fixture);
        verifyNoInteractions(inventoryRepository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 없는_주문은_사전조회와_취소_모두_ORDER_NOT_FOUND이다(boolean preflight) {
        // given
        if (preflight) {
            given(orderRepository.findWithOrderItemsById(ORDER_ID)).willReturn(Optional.empty());
        } else {
            given(orderRepository.findForUpdateById(ORDER_ID)).willReturn(Optional.empty());
        }

        // when
        // then
        assertError(ErrorCode.ORDER_NOT_FOUND, () -> execute(preflight, USER_ID));
        verifyNoInteractions(paymentRepository, inventoryRepository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 다른_사용자의_주문은_사전조회와_취소_모두_접근을_거부한다(boolean preflight) {
        // given
        Fixture fixture = fixture();
        stubOrder(fixture, preflight);

        // when
        // then
        assertError(ErrorCode.ORDER_ACCESS_DENIED, () -> execute(preflight, 99L));
        assertUnchanged(fixture);
        verifyNoInteractions(paymentRepository, inventoryRepository);
    }

    @ParameterizedTest
    @MethodSource("closedOrders")
    void 결제_전_상태가_아니면_사전조회와_취소를_거부한다(OrderStatus status, boolean preflight) {
        // given
        Fixture fixture = fixture();
        closeOrder(fixture, status);
        stubOrder(fixture, preflight);

        // when
        // then
        assertError(ErrorCode.INVALID_ORDER_STATUS, () -> execute(preflight, USER_ID));
        assertThat(fixture.order().getStatus()).isEqualTo(status);
        verifyNoInteractions(paymentRepository, inventoryRepository);
    }

    static Stream<Arguments> closedOrders() {
        return Stream.of(OrderStatus.PAID, OrderStatus.PAYMENT_FAILED, OrderStatus.CANCELED)
                .flatMap(status -> Stream.of(Arguments.of(status, true), Arguments.of(status, false)));
    }

    @ParameterizedTest
    @MethodSource("blockedPayments")
    void 결제_확인중이거나_결제완료이면_선점을_해제하지_않는다(PaymentStatus status, boolean preflight) {
        // given
        Fixture fixture = fixture();
        stubOrder(fixture, preflight);
        given(paymentRepository.findTopByOrderIdOrderByIdDesc(ORDER_ID))
                .willReturn(Optional.of(payment(status)));

        // when
        // then
        assertError(ErrorCode.INVALID_ORDER_STATUS, () -> execute(preflight, USER_ID));
        assertUnchanged(fixture);
        verifyNoInteractions(inventoryRepository);
    }

    static Stream<Arguments> blockedPayments() {
        return Stream.of(PaymentStatus.CONFIRM_PENDING, PaymentStatus.PAID)
                .flatMap(status -> Stream.of(Arguments.of(status, true), Arguments.of(status, false)));
    }

    @Test
    void 사전조회_후_결제가_확인대기로_바뀌면_취소시_다시_검증한다() {
        // given
        Fixture fixture = fixture();
        stubOrder(fixture, false);
        Payment payment = payment(PaymentStatus.READY);
        given(paymentRepository.findTopByOrderIdOrderByIdDesc(ORDER_ID)).willReturn(Optional.of(payment));
        assertThat(command.findPaymentIdForCancellation(USER_ID, ORDER_ID)).contains(PORTONE_ID);

        // when
        payment.startConfirm();

        // then
        assertError(ErrorCode.INVALID_ORDER_STATUS, this::cancel);
        assertUnchanged(fixture);
        verifyNoInteractions(inventoryRepository);
        assertThat(transactionManager.events).containsExactly("begin", "rollback");
    }

    @Test
    void 사전조회_후_주문이_결제완료로_바뀌면_취소시_다시_검증한다() {
        // given
        Fixture fixture = fixture();
        stubOrder(fixture, false);
        given(paymentRepository.findTopByOrderIdOrderByIdDesc(ORDER_ID))
                .willReturn(Optional.of(payment(PaymentStatus.READY)));
        assertThat(command.findPaymentIdForCancellation(USER_ID, ORDER_ID)).contains(PORTONE_ID);

        // when
        closeOrder(fixture, OrderStatus.PAID);

        // then
        assertError(ErrorCode.INVALID_ORDER_STATUS, this::cancel);
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void 같은_주문을_다시_취소하면_재고를_중복으로_해제하지_않는다() {
        // given
        Fixture fixture = fixture();
        stubOrder(fixture, false);
        stubInventories(fixture);
        given(paymentRepository.findTopByOrderIdOrderByIdDesc(ORDER_ID)).willReturn(Optional.empty());

        // when
        cancel();

        // then
        assertError(ErrorCode.INVALID_ORDER_STATUS, this::cancel);
        then(inventoryRepository).should().findInventoryByProduct_Id(10L);
        then(inventoryRepository).should().findInventoryByProduct_Id(20L);
        assertQuantities(fixture.firstInventory(), 10, 1, 9);
        assertQuantities(fixture.secondInventory(), 20, 1, 19);
        assertThat(transactionManager.events).containsExactly("begin", "commit", "begin", "rollback");
    }

    @Test
    void 재고_해제_실패는_예외를_전달하고_트랜잭션을_롤백한다() {
        // given
        Fixture fixture = fixture();
        fixture.secondInventory().release(2);
        stubOrder(fixture, false);
        stubInventories(fixture);
        given(paymentRepository.findTopByOrderIdOrderByIdDesc(ORDER_ID)).willReturn(Optional.empty());

        // when
        // then
        assertError(ErrorCode.INVALID_STOCK, this::cancel);
        assertThat(transactionManager.events).containsExactly("begin", "rollback");
        // DB 없이 실행하므로 메모리 객체의 원복 여부는 검증하지 않는다.
    }

    private Fixture fixture() {
        given(user.getId()).willReturn(USER_ID);
        Order order = Order.of(user, "QUOTE-170", "사과와 배", ActorType.USER, null, 7_000L,
                ShippingAddress.of("홍길동", "01012345678", "12345", "서울 주소"));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        OrderItem.of(order, secondProduct, "배", "1개", 2_000L, 2, 4_000L);
        OrderItem.of(order, firstProduct, "사과", "1개", 1_000L, 3, 3_000L);
        Inventory first = Inventory.of(firstProduct, 10);
        Inventory second = Inventory.of(secondProduct, 20);
        first.reserve(4);
        second.reserve(3);
        return new Fixture(order, first, second);
    }

    private void stubOrder(Fixture fixture, boolean preflightOnly) {
        given(orderRepository.findWithOrderItemsById(ORDER_ID)).willReturn(Optional.of(fixture.order()));
        if (!preflightOnly) {
            given(orderRepository.findForUpdateById(ORDER_ID)).willReturn(Optional.of(fixture.order()));
        }
    }

    private void stubInventories(Fixture fixture) {
        given(firstProduct.getId()).willReturn(10L);
        given(secondProduct.getId()).willReturn(20L);
        given(inventoryRepository.findInventoryByProduct_Id(10L)).willReturn(Optional.of(fixture.firstInventory()));
        given(inventoryRepository.findInventoryByProduct_Id(20L)).willReturn(Optional.of(fixture.secondInventory()));
    }

    private Payment payment(PaymentStatus status) {
        Payment payment = Payment.of(ORDER_ID, PORTONE_ID, 7_000L);
        if (status != PaymentStatus.READY) {
            payment.startConfirm();
        }
        if (status == PaymentStatus.PAID) {
            payment.complete(7_000L, LocalDateTime.of(2030, 1, 2, 12, 0));
        }
        return payment;
    }

    private void closeOrder(Fixture fixture, OrderStatus status) {
        if (status == OrderStatus.PAID) {
            fixture.order().pay();
            fixture.order().getOrderItems().forEach(OrderItem::deduct);
            fixture.firstInventory().deduct(3);
            fixture.secondInventory().deduct(2);
        } else {
            if (status == OrderStatus.PAYMENT_FAILED) {
                fixture.order().failPayment();
            } else {
                fixture.order().cancel();
            }
            fixture.order().getOrderItems().forEach(OrderItem::release);
            fixture.firstInventory().release(3);
            fixture.secondInventory().release(2);
        }
    }

    private void execute(boolean preflight, Long userId) {
        if (preflight) {
            command.findPaymentIdForCancellation(userId, ORDER_ID);
        } else {
            transactionTemplate.executeWithoutResult(status -> command.cancelBeforePayment(userId, ORDER_ID));
        }
    }

    private CancelOrderResponse cancel() {
        return transactionTemplate.execute(status -> command.cancelBeforePayment(USER_ID, ORDER_ID));
    }

    private void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
    }

    private void assertUnchanged(Fixture fixture) {
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsOnly(StockState.RESERVED);
        assertQuantities(fixture.firstInventory(), 10, 4, 6);
        assertQuantities(fixture.secondInventory(), 20, 3, 17);
    }

    private void assertQuantities(Inventory inventory, int stock, int reserved, int available) {
        assertThat(inventory.getStockQuantity()).isEqualTo(stock);
        assertThat(inventory.getReservedQuantity()).isEqualTo(reserved);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(available);
    }

    private record Fixture(Order order, Inventory firstInventory, Inventory secondInventory) {
    }
}
