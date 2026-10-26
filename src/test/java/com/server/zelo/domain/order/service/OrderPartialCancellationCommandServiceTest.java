package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.service.ChatRoomService;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderPartialCancellationCommandServiceTest {

    private static final Long ORDER_ID = 101L;
    private static final Long FIRST_ITEM_ID = 1_001L;
    private static final Long SECOND_ITEM_ID = 1_002L;

    @Mock private User user;
    @Mock private Product firstProduct;
    @Mock private Product secondProduct;
    @Mock private OrderRepository orderRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private OrderConfirmationRepository orderConfirmationRepository;
    @Mock private UserService userService;
    @Mock private QuoteService quoteService;
    @Mock private ProductService productService;
    @Mock private PaymentService paymentService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private UserAddressService userAddressService;
    @Mock private TransactionTemplate transactionTemplate;

    private OrderCommandService command;

    @BeforeEach
    void setUp() {
        OrderService orders = new OrderService(orderRepository, orderConfirmationRepository);
        InventoryService inventories = new InventoryService(inventoryRepository);
        command = new OrderCommandService(userService, orders, quoteService, productService,
                paymentService, chatRoomService, inventories, userAddressService, transactionTemplate);
    }

    @Test
    void 부분_취소는_요청한_품목의_수량만_복원하고_다른_주문의_선점을_유지한다() {
        // given
        Fixture fixture = createPaidFixture();
        stubOrder(fixture);
        stubProductIds();
        stubFirstInventory(fixture);

        // when
        command.cancelItems(ORDER_ID, Map.of(FIRST_ITEM_ID, 1));

        // then
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(fixture.firstItem().getCanceledQuantity()).isEqualTo(1);
        assertThat(fixture.firstItem().getCancelableQuantity()).isEqualTo(2);
        assertThat(fixture.firstItem().getStockState()).isEqualTo(StockState.DEDUCTED);
        assertThat(fixture.secondItem().getCanceledQuantity()).isZero();
        assertThat(fixture.secondItem().getStockState()).isEqualTo(StockState.DEDUCTED);
        assertQuantities(fixture.firstInventory(), 8, 1, 7);
        assertQuantities(fixture.secondInventory(), 18, 1, 17);
        then(inventoryRepository).should(never()).findInventoryByProduct_Id(10L);
        assertSnapshots(fixture);
        verifyNoInteractions(paymentService, quoteService, userService, productService,
                chatRoomService, userAddressService, orderConfirmationRepository);
    }

    @Test
    void 한_품목을_전량_취소해도_다른_품목이_남으면_주문은_결제_완료를_유지한다() {
        // given
        Fixture fixture = createPaidFixture();
        stubOrder(fixture);
        stubProductIds();
        stubFirstInventory(fixture);

        // when
        command.cancelItems(ORDER_ID, Map.of(FIRST_ITEM_ID, 3));

        // then
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(fixture.firstItem().getStockState()).isEqualTo(StockState.RESTORED);
        assertThat(fixture.firstItem().getCanceledQuantity()).isEqualTo(3);
        assertThat(fixture.secondItem().getStockState()).isEqualTo(StockState.DEDUCTED);
        assertQuantities(fixture.firstInventory(), 10, 1, 9);
        assertQuantities(fixture.secondInventory(), 18, 1, 17);
    }

    @Test
    void 모든_품목을_전량_취소하면_주문도_취소하고_재고는_한_번만_복원한다() {
        // given
        Fixture fixture = createPaidFixture();
        stubOrder(fixture);
        stubProductIds();
        stubFirstInventory(fixture);
        stubSecondInventory(fixture);

        // when
        command.cancelItems(ORDER_ID, Map.of(FIRST_ITEM_ID, 3, SECOND_ITEM_ID, 2));

        // then
        assertFullyCanceled(fixture);
        then(inventoryRepository).should().findInventoryByProduct_Id(10L);
        then(inventoryRepository).should().findInventoryByProduct_Id(20L);
        then(inventoryRepository).shouldHaveNoMoreInteractions();
        assertSnapshots(fixture);
    }

    @Test
    void 여러_차례_부분_취소한_뒤_마지막_잔여_수량을_취소하면_주문이_취소된다() {
        // given
        Fixture fixture = createPaidFixture();
        stubOrder(fixture);
        stubProductIds();
        stubFirstInventory(fixture);
        stubSecondInventory(fixture);
        command.cancelItems(ORDER_ID, Map.of(FIRST_ITEM_ID, 1, SECOND_ITEM_ID, 1));

        // when
        command.cancelItems(ORDER_ID, Map.of(FIRST_ITEM_ID, 2, SECOND_ITEM_ID, 1));

        // then
        assertFullyCanceled(fixture);
        assertSnapshots(fixture);
    }

    @Test
    void 주문을_먼저_잠그고_요청_순서와_달라도_상품_ID_오름차순으로_복원한다() {
        // given
        Fixture fixture = createPaidFixture();
        stubOrder(fixture);
        stubProductIds();
        stubFirstInventory(fixture);
        stubSecondInventory(fixture);
        Map<Long, Integer> requests = new LinkedHashMap<>();
        requests.put(FIRST_ITEM_ID, 1);
        requests.put(SECOND_ITEM_ID, 1);

        // when
        command.cancelItems(ORDER_ID, requests);

        // then
        InOrder calls = inOrder(orderRepository, inventoryRepository);
        calls.verify(orderRepository).findForUpdateById(ORDER_ID);
        calls.verify(orderRepository).findWithOrderItemsById(ORDER_ID);
        calls.verify(inventoryRepository).findInventoryByProduct_Id(10L);
        calls.verify(inventoryRepository).findInventoryByProduct_Id(20L);
        assertThat(fixture.order().getOrderItems())
                .containsExactly(fixture.firstItem(), fixture.secondItem());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, 4})
    void 잘못된_취소_수량은_품목과_재고를_변경하지_않는다(Integer quantity) {
        // given
        Fixture fixture = createPaidFixture();
        stubOrder(fixture);
        Map<Long, Integer> requests = new HashMap<>();
        requests.put(FIRST_ITEM_ID, quantity);

        // when
        // then
        assertError(() -> command.cancelItems(ORDER_ID, requests), ErrorCode.INVALID_REFUND_QUANTITY);
        assertUnchangedPaidFixture(fixture);
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void 구매_수량_이하여도_이미_취소한_수량을_합쳐_초과하면_거부한다() {
        // given
        Fixture fixture = createPaidFixture();
        fixture.firstItem().cancelQuantity(2);
        fixture.firstInventory().restore(2);
        stubOrder(fixture);

        // when
        // then
        assertError(() -> command.cancelItems(ORDER_ID, Map.of(FIRST_ITEM_ID, 2)),
                ErrorCode.INVALID_REFUND_QUANTITY);
        assertThat(fixture.firstItem().getCanceledQuantity()).isEqualTo(2);
        assertThat(fixture.firstItem().getStockState()).isEqualTo(StockState.DEDUCTED);
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PAID);
        assertQuantities(fixture.firstInventory(), 9, 1, 8);
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void 뒤에_있는_요청이_잘못돼도_앞의_정상_품목을_먼저_변경하지_않는다() {
        // given
        Fixture fixture = createPaidFixture();
        stubOrder(fixture);
        Map<Long, Integer> requests = new LinkedHashMap<>();
        requests.put(FIRST_ITEM_ID, 1);
        requests.put(SECOND_ITEM_ID, 3);

        // when
        // then
        assertError(() -> command.cancelItems(ORDER_ID, requests), ErrorCode.INVALID_REFUND_QUANTITY);
        assertUnchangedPaidFixture(fixture);
        verifyNoInteractions(inventoryRepository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, 9_999L})
    void 잘못된_품목_ID나_주문에_없는_품목이면_거부한다(Long itemId) {
        // given
        Fixture fixture = createPaidFixture();
        stubOrder(fixture);
        Map<Long, Integer> requests = new HashMap<>();
        requests.put(itemId, 1);

        // when
        // then
        assertError(() -> command.cancelItems(ORDER_ID, requests), ErrorCode.INVALID_INPUT);
        assertUnchangedPaidFixture(fixture);
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void 다른_주문의_실제_품목_ID도_현재_주문에서_취소할_수_없다() {
        // given
        Fixture fixture = createPaidFixture();
        Order anotherOrder = createOrder("QUOTE-OTHER");
        OrderItem foreignItem = OrderItem.of(anotherOrder, firstProduct, "사과", "1개", 1_000L, 1, 1_000L);
        ReflectionTestUtils.setField(foreignItem, "id", 9_999L);
        foreignItem.deduct();
        stubOrder(fixture);

        // when
        // then
        assertError(() -> command.cancelItems(ORDER_ID, Map.of(foreignItem.getId(), 1)),
                ErrorCode.INVALID_INPUT);
        assertUnchangedPaidFixture(fixture);
        assertThat(foreignItem.getCanceledQuantity()).isZero();
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void 빈_요청은_주문을_조회하기_전에_거부한다() {
        // given
        Map<Long, Integer> requests = Map.of();

        // when
        // then
        assertError(() -> command.cancelItems(ORDER_ID, requests), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(orderRepository, inventoryRepository);
    }

    @Test
    void null_요청은_주문을_조회하기_전에_거부한다() {
        // given
        Map<Long, Integer> requests = null;

        // when
        // then
        assertError(() -> command.cancelItems(ORDER_ID, requests), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(orderRepository, inventoryRepository);
    }

    @Test
    void 주문이_없으면_404_오류를_전달하고_재고를_변경하지_않는다() {
        // given
        given(orderRepository.findForUpdateById(ORDER_ID)).willReturn(Optional.empty());

        // when
        // then
        assertError(() -> command.cancelItems(ORDER_ID, Map.of(FIRST_ITEM_ID, 1)),
                ErrorCode.ORDER_NOT_FOUND);
        then(orderRepository).should(never()).findWithOrderItemsById(ORDER_ID);
        verifyNoInteractions(inventoryRepository);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAYMENT_PENDING", "PAYMENT_FAILED", "CANCELED"})
    void 결제_완료_이외의_주문_상태에서는_부분_취소를_거부한다(OrderStatus status) {
        // given
        Fixture fixture = createFixture();
        if (status == OrderStatus.PAYMENT_FAILED) {
            fixture.order().failPayment();
        } else if (status == OrderStatus.CANCELED) {
            fixture.order().cancel();
        }
        stubOrder(fixture);

        // when
        // then
        assertError(() -> command.cancelItems(ORDER_ID, Map.of(FIRST_ITEM_ID, 1)),
                ErrorCode.INVALID_ORDER_STATUS);
        assertThat(fixture.order().getStatus()).isEqualTo(status);
        assertThat(fixture.firstItem().getCanceledQuantity()).isZero();
        assertThat(fixture.firstItem().getStockState()).isEqualTo(StockState.RESERVED);
        assertQuantities(fixture.firstInventory(), 10, 4, 6);
        verifyNoInteractions(inventoryRepository);
    }

    @ParameterizedTest
    @EnumSource(value = StockState.class, names = {"RESERVED", "RELEASED", "RESTORED"})
    void 차감_확정_이외의_품목은_부분_취소를_거부한다(StockState state) {
        // given
        Fixture fixture = createFixture();
        fixture.order().pay();
        switch (state) {
            case RESERVED -> { }
            case RELEASED -> fixture.firstItem().release();
            case RESTORED -> {
                fixture.firstItem().deduct();
                fixture.firstItem().restore();
            }
            default -> throw new IllegalArgumentException(state.name());
        }
        Integer previousCanceled = fixture.firstItem().getCanceledQuantity();
        stubOrder(fixture);

        // when
        // then
        assertError(() -> command.cancelItems(ORDER_ID, Map.of(FIRST_ITEM_ID, 1)),
                ErrorCode.INVALID_STOCK_STATUS);
        assertThat(fixture.firstItem().getCanceledQuantity()).isEqualTo(previousCanceled);
        assertThat(fixture.firstItem().getStockState()).isEqualTo(state);
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(inventoryRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "cancelIfPossible"})
    void 부분_취소_이후_전체_취소는_남은_수량만_복원한다(String operation) {
        // given
        Fixture fixture = createPaidFixture();
        fixture.firstItem().cancelQuantity(1);
        fixture.firstInventory().restore(1);
        stubOrder(fixture);
        stubProductIds();
        stubFirstInventory(fixture);
        stubSecondInventory(fixture);

        // when
        if (operation.equals("cancel")) {
            command.cancel(ORDER_ID);
        } else {
            command.cancelIfPossible(ORDER_ID);
        }

        // then
        assertFullyCanceled(fixture);
        assertSnapshots(fixture);
    }

    @Test
    void 한_품목이_이미_전량_복원됐어도_전체_취소는_나머지만_복원한다() {
        // given
        Fixture fixture = createPaidFixture();
        fixture.firstItem().restore();
        fixture.firstInventory().restore(3);
        stubOrder(fixture);
        stubProductIds();
        stubSecondInventory(fixture);

        // when
        command.cancel(ORDER_ID);

        // then
        assertFullyCanceled(fixture);
        then(inventoryRepository).should(never()).findInventoryByProduct_Id(20L);
        then(inventoryRepository).should().findInventoryByProduct_Id(10L);
    }

    @Test
    void 전량_취소된_주문에_부분_취소를_재호출해도_재고를_중복_복원하지_않는다() {
        // given
        Fixture fixture = createPaidFixture();
        fixture.firstItem().restore();
        fixture.secondItem().restore();
        fixture.firstInventory().restore(3);
        fixture.secondInventory().restore(2);
        fixture.order().cancel();
        stubOrder(fixture);

        // when
        // then
        assertError(() -> command.cancelItems(ORDER_ID, Map.of(FIRST_ITEM_ID, 1)),
                ErrorCode.INVALID_ORDER_STATUS);
        assertFullyCanceled(fixture);
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void 재고_복원_실패는_호출자에게_전달되어_바깥_트랜잭션이_롤백된다() {
        // given
        Fixture fixture = createPaidFixture();
        stubOrder(fixture);
        stubProductIds();
        stubSecondInventory(fixture);
        given(inventoryRepository.findInventoryByProduct_Id(20L)).willReturn(Optional.empty());
        OrderTestTransactionManager manager = new OrderTestTransactionManager();
        TransactionTemplate transaction = new TransactionTemplate(manager);

        // when
        // then
        assertError(() -> transaction.executeWithoutResult(status -> command.cancelItems(
                ORDER_ID, Map.of(FIRST_ITEM_ID, 1, SECOND_ITEM_ID, 1))), ErrorCode.PRODUCT_NOT_FOUND);
        assertThat(manager.events).containsExactly("begin", "rollback");
    }

    private Fixture createFixture() {
        Order order = createOrder("QUOTE-PARTIAL-187");
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        OrderItem first = OrderItem.of(order, firstProduct, "사과", "1개", 1_000L, 3, 3_000L);
        OrderItem second = OrderItem.of(order, secondProduct, "배", "1개", 2_000L, 2, 4_000L);
        ReflectionTestUtils.setField(first, "id", FIRST_ITEM_ID);
        ReflectionTestUtils.setField(second, "id", SECOND_ITEM_ID);
        Inventory firstInventory = Inventory.of(firstProduct, 10);
        Inventory secondInventory = Inventory.of(secondProduct, 20);
        firstInventory.reserve(4);
        secondInventory.reserve(3);
        return new Fixture(order, first, second, firstInventory, secondInventory);
    }

    private Fixture createPaidFixture() {
        Fixture fixture = createFixture();
        fixture.order().pay();
        fixture.firstItem().deduct();
        fixture.secondItem().deduct();
        fixture.firstInventory().deduct(3);
        fixture.secondInventory().deduct(2);
        return fixture;
    }

    private Order createOrder(String quoteId) {
        return Order.of(user, quoteId, "사과와 배", ActorType.USER, null, 7_000L,
                ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울 강남구"));
    }

    private void stubOrder(Fixture fixture) {
        given(orderRepository.findForUpdateById(ORDER_ID)).willReturn(Optional.of(fixture.order()));
        given(orderRepository.findWithOrderItemsById(ORDER_ID)).willReturn(Optional.of(fixture.order()));
    }

    private void stubProductIds() {
        given(firstProduct.getId()).willReturn(20L);
        given(secondProduct.getId()).willReturn(10L);
    }

    private void stubFirstInventory(Fixture fixture) {
        given(inventoryRepository.findInventoryByProduct_Id(20L))
                .willReturn(Optional.of(fixture.firstInventory()));
    }

    private void stubSecondInventory(Fixture fixture) {
        given(inventoryRepository.findInventoryByProduct_Id(10L))
                .willReturn(Optional.of(fixture.secondInventory()));
    }

    private void assertUnchangedPaidFixture(Fixture fixture) {
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getCanceledQuantity)
                .containsExactly(0, 0);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsExactly(StockState.DEDUCTED, StockState.DEDUCTED);
        assertQuantities(fixture.firstInventory(), 7, 1, 6);
        assertQuantities(fixture.secondInventory(), 18, 1, 17);
    }

    private void assertFullyCanceled(Fixture fixture) {
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.order().isFullyCanceled()).isTrue();
        assertThat(fixture.firstItem().getCanceledQuantity()).isEqualTo(3);
        assertThat(fixture.secondItem().getCanceledQuantity()).isEqualTo(2);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsExactly(StockState.RESTORED, StockState.RESTORED);
        assertQuantities(fixture.firstInventory(), 10, 1, 9);
        assertQuantities(fixture.secondInventory(), 20, 1, 19);
    }

    private void assertSnapshots(Fixture fixture) {
        assertThat(fixture.firstItem().getQuantity()).isEqualTo(3);
        assertThat(fixture.firstItem().getUnitPrice()).isEqualTo(1_000L);
        assertThat(fixture.firstItem().getLineAmount()).isEqualTo(3_000L);
        assertThat(fixture.secondItem().getQuantity()).isEqualTo(2);
        assertThat(fixture.secondItem().getUnitPrice()).isEqualTo(2_000L);
        assertThat(fixture.secondItem().getLineAmount()).isEqualTo(4_000L);
        assertThat(fixture.order().getTotalAmount()).isEqualTo(7_000L);
    }

    private void assertQuantities(Inventory inventory, int stock, int reserved, int available) {
        assertThat(inventory.getStockQuantity()).isEqualTo(stock);
        assertThat(inventory.getReservedQuantity()).isEqualTo(reserved);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(available);
        assertThat(inventory.getAvailableQuantity())
                .isEqualTo(inventory.getStockQuantity() - inventory.getReservedQuantity());
    }

    private void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private record Fixture(Order order, OrderItem firstItem, OrderItem secondItem,
                           Inventory firstInventory, Inventory secondInventory) {
    }
}
