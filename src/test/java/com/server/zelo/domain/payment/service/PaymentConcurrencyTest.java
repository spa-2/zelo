package com.server.zelo.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import com.server.zelo.domain.delivery.entity.Delivery;
import com.server.zelo.domain.delivery.repository.DeliveryRepository;
import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.support.Concurrency;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(Concurrency.class)
class PaymentConcurrencyTest {

    private static final int THREAD_COUNT = 10;

    @MockitoSpyBean
    private PortOneClient portOneClient;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private Concurrency fixture;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    private Concurrency.SavedOrder savedOrder;

    @AfterEach
    void tearDown() {
        fixture.delete(savedOrder);
    }

    @Test
    void 같은_주문에_재결제를_동시에_요청해도_새_결제는_하나만_생긴다() throws InterruptedException {
        // given
        savedOrder = fixture.saveOrderWithFailedPayment();
        Set<String> portoneIds = ConcurrentHashMap.newKeySet();

        // when
        int successCount = fixture.runConcurrently(THREAD_COUNT, () -> portoneIds.add(
                paymentFacade.retryPayment(savedOrder.userId(), savedOrder.orderId()).portoneId()));

        // then
        List<Payment> payments = findPayments();
        assertThat(successCount).isEqualTo(THREAD_COUNT);
        assertThat(payments).hasSize(2);
        assertThat(payments).filteredOn(Payment::isReady).hasSize(1);
        assertThat(portoneIds).hasSize(1);
    }

    // 프론트 확인·웹훅·대사 스케줄러가 같은 결제를 동시에 확인하는 상황
    @Test
    void 같은_결제를_동시에_확인해도_포트원_조회와_재고_차감은_한_번만_일어난다() throws InterruptedException {
        // given
        savedOrder = fixture.saveOrderWithReadyPayment();
        Set<PaymentStatus> results = ConcurrentHashMap.newKeySet();

        // when
        int successCount = fixture.runConcurrently(THREAD_COUNT,
                () -> results.add(paymentFacade.confirmPayment(savedOrder.portoneId())));

        // then
        Inventory inventory = inventoryRepository.findById(savedOrder.stock().inventoryId()).orElseThrow();
        assertThat(successCount).isEqualTo(THREAD_COUNT);
        assertThat(results).containsExactly(PaymentStatus.PAID);
        then(portOneClient).should(times(1)).getPayment(savedOrder.portoneId());
        assertThat(orderRepository.findById(savedOrder.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(inventory.getStockQuantity()).isEqualTo(9);
        assertThat(inventory.getReservedQuantity()).isZero();
        assertThat(findDeliveries()).hasSize(1);
    }

    private List<Payment> findPayments() {
        return paymentRepository.findAll().stream()
                .filter(payment -> payment.getOrderId().equals(savedOrder.orderId()))
                .toList();
    }

    private List<Delivery> findDeliveries() {
        return deliveryRepository.findAll().stream()
                .filter(delivery -> delivery.getOrderId().equals(savedOrder.orderId()))
                .toList();
    }
}