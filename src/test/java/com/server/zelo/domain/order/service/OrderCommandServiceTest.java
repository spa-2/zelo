package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.entity.StockState;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderCommandServiceTest {

    private static final Long ORDER_ID = 101L;

    @Mock
    private User user;

    @Mock
    private Product firstProduct;

    @Mock
    private Product secondProduct;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    private OrderCommandService orderCommandService;

    @BeforeEach
    void setUp() {
        // 수량 변경까지 검증하기 위해 Service와 엔티티는 실제 객체를 사용한다.
        OrderService orderService = new OrderService(orderRepository);
        InventoryService inventoryService = new InventoryService(inventoryRepository);
        orderCommandService = new OrderCommandService(orderService, inventoryService);
    }

    @Test
    void 결제_완료는_모든_품목의_주문_수량만_차감하고_다른_주문의_선점을_유지한다() {
        // given
        Fixture fixture = createFixture();
        stubOrder(fixture);
        stubInventories(fixture);

        // when
        orderCommandService.completePayment(ORDER_ID);

        // then
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsExactly(StockState.DEDUCTED, StockState.DEDUCTED);
        assertQuantities(fixture.firstInventory(), 7, 1, 6);
        assertQuantities(fixture.secondInventory(), 18, 1, 17);
    }

    @Test
    void 결제_실패는_실재고를_유지하고_모든_품목의_선점을_해제한다() {
        // given
        Fixture fixture = createFixture();
        stubOrder(fixture);
        stubInventories(fixture);

        // when
        orderCommandService.failPayment(ORDER_ID);

        // then
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsExactly(StockState.RELEASED, StockState.RELEASED);
        assertQuantities(fixture.firstInventory(), 10, 1, 9);
        assertQuantities(fixture.secondInventory(), 20, 1, 19);
    }

    @Test
    void 결제_대기_주문을_취소하면_선점을_해제하고_실재고는_늘리지_않는다() {
        // given
        Fixture fixture = createFixture();
        stubOrder(fixture);
        stubInventories(fixture);

        // when
        orderCommandService.cancel(ORDER_ID);

        // then
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsExactly(StockState.RELEASED, StockState.RELEASED);
        assertQuantities(fixture.firstInventory(), 10, 1, 9);
        assertQuantities(fixture.secondInventory(), 20, 1, 19);
    }

    @Test
    void 결제_완료_후_취소하면_차감한_재고를_복원하고_다른_주문의_선점은_유지한다() {
        // given
        Fixture fixture = createFixture();
        stubOrder(fixture);
        stubInventories(fixture);
        orderCommandService.completePayment(ORDER_ID);

        // when
        orderCommandService.cancel(ORDER_ID);

        // then
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsExactly(StockState.RESTORED, StockState.RESTORED);
        assertQuantities(fixture.firstInventory(), 10, 1, 9);
        assertQuantities(fixture.secondInventory(), 20, 1, 19);
    }

    @Test
    void 취소는_품목별_상태에_따라_차감_재고는_복원하고_남은_선점은_해제한다() {
        // given
        Fixture fixture = createFixture();
        fixture.order().pay();
        fixture.firstItem().deduct();
        fixture.firstInventory().deduct(3);
        stubOrder(fixture);
        stubInventories(fixture);

        // when
        orderCommandService.cancel(ORDER_ID);

        // then
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.firstItem().getStockState()).isEqualTo(StockState.RESTORED);
        assertThat(fixture.secondItem().getStockState()).isEqualTo(StockState.RELEASED);
        assertQuantities(fixture.firstInventory(), 10, 1, 9);
        assertQuantities(fixture.secondInventory(), 20, 1, 19);
    }

    @Test
    void 취소할_때_이미_해제되거나_복원된_품목의_재고는_다시_변경하지_않는다() {
        // given
        Fixture fixture = createFixture();
        fixture.firstItem().release();
        fixture.firstInventory().release(3);
        fixture.secondItem().deduct();
        fixture.secondInventory().deduct(2);
        fixture.secondItem().restore();
        fixture.secondInventory().restore(2);
        stubOrder(fixture);

        // when
        orderCommandService.cancel(ORDER_ID);

        // then
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.firstItem().getStockState()).isEqualTo(StockState.RELEASED);
        assertThat(fixture.secondItem().getStockState()).isEqualTo(StockState.RESTORED);
        assertQuantities(fixture.firstInventory(), 10, 1, 9);
        assertQuantities(fixture.secondInventory(), 20, 1, 19);
        verifyNoInteractions(inventoryRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"completePayment", "failPayment", "cancel"})
    void 같은_결과를_중복_처리하면_예외가_발생하고_재고는_한_번만_변경된다(String operation) {
        // given
        Fixture fixture = createFixture();
        stubOrder(fixture);
        stubInventories(fixture);
        execute(operation);
        OrderStatus previousStatus = fixture.order().getStatus();
        StockState previousState = fixture.firstItem().getStockState();
        int firstStock = fixture.firstInventory().getStockQuantity();
        int firstReserved = fixture.firstInventory().getReservedQuantity();
        int firstAvailable = fixture.firstInventory().getAvailableQuantity();
        int secondStock = fixture.secondInventory().getStockQuantity();
        int secondReserved = fixture.secondInventory().getReservedQuantity();
        int secondAvailable = fixture.secondInventory().getAvailableQuantity();

        // when
        // then
        assertThatThrownBy(() -> execute(operation))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_STATUS));
        assertThat(fixture.order().getStatus()).isEqualTo(previousStatus);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsExactly(previousState, previousState);
        assertQuantities(fixture.firstInventory(), firstStock, firstReserved, firstAvailable);
        assertQuantities(fixture.secondInventory(), secondStock, secondReserved, secondAvailable);
        then(inventoryRepository).should(times(1)).findInventoryByProduct_Id(7L);
        then(inventoryRepository).should(times(1)).findInventoryByProduct_Id(9L);
    }

    @Test
    void 환불_후_취소를_다시_호출해도_실재고를_중복_복원하지_않는다() {
        // given
        Fixture fixture = createFixture();
        stubOrder(fixture);
        stubInventories(fixture);
        orderCommandService.completePayment(ORDER_ID);
        orderCommandService.cancel(ORDER_ID);

        // when
        // then
        assertThatThrownBy(() -> orderCommandService.cancel(ORDER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_STATUS));
        assertThat(fixture.order().getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsExactly(StockState.RESTORED, StockState.RESTORED);
        assertQuantities(fixture.firstInventory(), 10, 1, 9);
        assertQuantities(fixture.secondInventory(), 20, 1, 19);
        then(inventoryRepository).should(times(2)).findInventoryByProduct_Id(7L);
        then(inventoryRepository).should(times(2)).findInventoryByProduct_Id(9L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"completePayment", "failPayment", "cancel"})
    void 주문이_없으면_예외를_전달하고_재고는_조회하지_않는다(String operation) {
        // given
        given(orderRepository.findWithOrderItemsById(ORDER_ID)).willReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> execute(operation))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        verifyNoInteractions(inventoryRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "PAID, completePayment",
            "PAID, failPayment",
            "PAYMENT_FAILED, completePayment",
            "PAYMENT_FAILED, failPayment",
            "PAYMENT_FAILED, cancel",
            "CANCELED, completePayment",
            "CANCELED, failPayment",
            "CANCELED, cancel"
    })
    void 허용하지_않은_주문_상태에서는_품목과_재고를_변경하지_않는다(
            OrderStatus initialStatus, String operation) {
        // given
        Fixture fixture = createFixture();
        prepareStatus(fixture, initialStatus);
        stubOrder(fixture);
        StockState previousState = fixture.firstItem().getStockState();

        // when
        // then
        assertThatThrownBy(() -> execute(operation))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_STATUS));
        assertThat(fixture.order().getStatus()).isEqualTo(initialStatus);
        assertThat(fixture.order().getOrderItems()).extracting(OrderItem::getStockState)
                .containsExactly(previousState, previousState);
        if (initialStatus == OrderStatus.PAID) {
            assertQuantities(fixture.firstInventory(), 7, 1, 6);
            assertQuantities(fixture.secondInventory(), 18, 1, 17);
        } else {
            assertQuantities(fixture.firstInventory(), 10, 1, 9);
            assertQuantities(fixture.secondInventory(), 20, 1, 19);
        }
        verifyNoInteractions(inventoryRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"completePayment", "failPayment", "cancel"})
    void 재고_조회_실패를_삼키지_않고_호출자에게_전달한다(String operation) {
        // given
        Fixture fixture = createFixture();
        stubOrder(fixture);
        given(firstProduct.getId()).willReturn(7L);
        given(inventoryRepository.findInventoryByProduct_Id(7L)).willReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> execute(operation))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertQuantities(fixture.firstInventory(), 10, 4, 6);
        assertQuantities(fixture.secondInventory(), 20, 3, 17);
    }

    private Fixture createFixture() {
        Order order = Order.of(user, "QUOTE-COMMAND", "사과와 배", ActorType.USER, null, 7_000L);
        OrderItem firstItem = OrderItem.of(order, firstProduct, "사과", "1개", 1_000L, 3, 3_000L);
        OrderItem secondItem = OrderItem.of(order, secondProduct, "배", "1개", 2_000L, 2, 4_000L);
        Inventory firstInventory = Inventory.of(firstProduct, 10);
        Inventory secondInventory = Inventory.of(secondProduct, 20);
        // 각 상품에 다른 주문의 선점 1개를 남겨 주문 수량만 처리하는지 확인한다.
        firstInventory.reserve(4);
        secondInventory.reserve(3);
        return new Fixture(order, firstItem, secondItem, firstInventory, secondInventory);
    }

    private void stubOrder(Fixture fixture) {
        given(orderRepository.findWithOrderItemsById(ORDER_ID)).willReturn(Optional.of(fixture.order()));
    }

    private void stubInventories(Fixture fixture) {
        given(firstProduct.getId()).willReturn(7L);
        given(secondProduct.getId()).willReturn(9L);
        given(inventoryRepository.findInventoryByProduct_Id(7L)).willReturn(Optional.of(fixture.firstInventory()));
        given(inventoryRepository.findInventoryByProduct_Id(9L)).willReturn(Optional.of(fixture.secondInventory()));
    }

    private void prepareStatus(Fixture fixture, OrderStatus status) {
        switch (status) {
            case PAID -> {
                fixture.order().pay();
                fixture.firstItem().deduct();
                fixture.secondItem().deduct();
                fixture.firstInventory().deduct(3);
                fixture.secondInventory().deduct(2);
            }
            case PAYMENT_FAILED, CANCELED -> {
                if (status == OrderStatus.PAYMENT_FAILED) {
                    fixture.order().failPayment();
                } else {
                    fixture.order().cancel();
                }
                fixture.firstItem().release();
                fixture.secondItem().release();
                fixture.firstInventory().release(3);
                fixture.secondInventory().release(2);
            }
            default -> throw new IllegalArgumentException(status.name());
        }
    }

    private void execute(String operation) {
        switch (operation) {
            case "completePayment" -> orderCommandService.completePayment(ORDER_ID);
            case "failPayment" -> orderCommandService.failPayment(ORDER_ID);
            case "cancel" -> orderCommandService.cancel(ORDER_ID);
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private void assertQuantities(Inventory inventory, int stock, int reserved, int available) {
        assertThat(inventory.getStockQuantity()).isEqualTo(stock);
        assertThat(inventory.getReservedQuantity()).isEqualTo(reserved);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(available);
        assertThat(inventory.getAvailableQuantity())
                .isEqualTo(inventory.getStockQuantity() - inventory.getReservedQuantity());
    }

    private record Fixture(Order order, OrderItem firstItem, OrderItem secondItem,
                           Inventory firstInventory, Inventory secondInventory) {
    }
}
