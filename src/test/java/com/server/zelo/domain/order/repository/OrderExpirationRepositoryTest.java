package com.server.zelo.domain.order.repository;

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
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import com.server.zelo.domain.payment.service.PaymentService;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.service.QuoteService;
import com.server.zelo.domain.refund.service.RefundService;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.service.UserAddressService;
import com.server.zelo.domain.user.service.UserService;
import com.server.zelo.global.config.AuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

// 실행마다 별도 스키마를 만들어 실제 PostgreSQL 쿼리와 잠금을 검증한다.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.sql.init.mode=never",
        "spring.jpa.show-sql=false"
})
@ActiveProfiles("test")
@Import(AuditingConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderExpirationRepositoryTest {

    private static final String TEST_SCHEMA = "order_expiration_" + UUID.randomUUID().toString().replace("-", "");
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2030, 1, 2, 12, 0);
    private static final List<PaymentStatus> BLOCKED =
            List.of(PaymentStatus.CONFIRM_PENDING, PaymentStatus.PAID);

    @Autowired private EntityManager entityManager;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> TEST_SCHEMA);
    }

    @Test
    void 만료_전은_제외하고_정확히_30분인_경계와_이전_주문은_포함한다() {
        // given
        Order older = saveOrder(CUTOFF.minusSeconds(1), OrderStatus.PAYMENT_PENDING);
        Order boundary = saveOrder(CUTOFF, OrderStatus.PAYMENT_PENDING);
        Order recent = saveOrder(CUTOFF.plusNanos(1_000), OrderStatus.PAYMENT_PENDING);

        // when
        List<Long> result = candidates(100);

        // then
        assertThat(result).containsExactly(older.getId(), boundary.getId());
        assertThat(eligible(older.getId())).isTrue();
        assertThat(eligible(boundary.getId())).isTrue();
        assertThat(eligible(recent.getId())).isFalse();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void 결제_대기_상태인_주문만_만료_대상이_된다(OrderStatus status) {
        // given
        Order order = saveOrder(CUTOFF.minusMinutes(1), status);
        boolean expected = status == OrderStatus.PAYMENT_PENDING;

        // when
        List<Long> result = candidates(100);

        // then
        assertThat(result.contains(order.getId())).isEqualTo(expected);
        assertThat(eligible(order.getId())).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void 확인_대기와_결제_완료_결제가_있으면_만료에서_제외한다(PaymentStatus status) {
        // given
        Order order = saveOrder(CUTOFF.minusMinutes(1), OrderStatus.PAYMENT_PENDING);
        savePayment(order.getId(), status);
        boolean expected = !BLOCKED.contains(status);

        // when
        List<Long> result = candidates(100);

        // then
        assertThat(result.contains(order.getId())).isEqualTo(expected);
        assertThat(eligible(order.getId())).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CONFIRM_PENDING", "PAID"})
    void 최신_결제가_READY여도_이전_보호_대상_결제가_있으면_제외한다(PaymentStatus blocked) {
        // given
        Order order = saveOrder(CUTOFF.minusMinutes(1), OrderStatus.PAYMENT_PENDING);
        savePayment(order.getId(), blocked);
        savePayment(order.getId(), PaymentStatus.READY);

        // when
        List<Long> result = candidates(100);

        // then
        assertThat(result).doesNotContain(order.getId());
        assertThat(eligible(order.getId())).isFalse();
    }

    @Test
    void 제외할_주문이_앞에_있어도_제한_개수만큼_실제_후보를_조회한다() {
        // given
        for (int i = 0; i < 3; i++) {
            Order blocked = saveOrder(CUTOFF.minusHours(1), OrderStatus.PAYMENT_PENDING);
            savePayment(blocked.getId(), PaymentStatus.CONFIRM_PENDING);
        }
        Order first = saveOrder(CUTOFF.minusMinutes(3), OrderStatus.PAYMENT_PENDING);
        Order second = saveOrder(CUTOFF.minusMinutes(2), OrderStatus.PAYMENT_PENDING);
        saveOrder(CUTOFF.minusMinutes(1), OrderStatus.PAYMENT_PENDING);

        // when
        List<Long> result = candidates(2);

        // then
        assertThat(result).containsExactly(first.getId(), second.getId());
    }

    @Test
    void 생성_시각이_오래된_순서로_조회하고_같은_시각이면_ID_오름차순이다() {
        // given
        Order first = saveOrder(CUTOFF.minusMinutes(1), OrderStatus.PAYMENT_PENDING);
        Order second = saveOrder(CUTOFF.minusMinutes(1), OrderStatus.PAYMENT_PENDING);
        Order oldest = saveOrder(CUTOFF.minusMinutes(2), OrderStatus.PAYMENT_PENDING);

        // when
        List<Long> result = candidates(100);

        // then
        assertThat(result).containsExactly(oldest.getId(), first.getId(), second.getId());
    }

    @Test
    void 후보_조회_후_결제확인이_시작되면_재검증에서_제외한다() {
        // given
        Order order = saveOrder(CUTOFF.minusMinutes(1), OrderStatus.PAYMENT_PENDING);
        Payment payment = savePayment(order.getId(), PaymentStatus.READY);
        assertThat(candidates(100)).contains(order.getId());

        // when
        payment.startConfirm();
        entityManager.flush();

        // then
        assertThat(eligible(order.getId())).isFalse();
    }

    @Test
    void 후보_조회_후_결제완료로_바뀌면_재검증에서_제외한다() {
        // given
        Order order = saveOrder(CUTOFF.minusMinutes(1), OrderStatus.PAYMENT_PENDING);
        assertThat(candidates(100)).contains(order.getId());

        // when
        order.pay();
        entityManager.flush();

        // then
        assertThat(eligible(order.getId())).isFalse();
    }

    @Test
    void 없는_주문은_만료_대상이_아니다() {
        // given
        Long missingOrderId = Long.MAX_VALUE;

        // when
        boolean result = eligible(missingOrderId);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 두_트랜잭션이_같은_주문을_처리해도_선점은_한_번만_해제한다() throws Exception {
        // given
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        FixtureIds fixture = transactions.execute(status -> saveReservedOrder(5));
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        OrderService service = new OrderService(orderRepository, mock(OrderConfirmationRepository.class)) {
            @Override
            public Order getOrderWithItemsForUpdate(Long orderId) {
                boolean first = attempts.incrementAndGet() == 1;
                if (!first) {
                    secondAttempted.countDown();
                }
                Order order = super.getOrderWithItemsForUpdate(orderId);
                if (first) {
                    firstLocked.countDown();
                    await(releaseFirst);
                }
                return order;
            }
        };
        OrderCommandService firstCommand = commandProxy(service);
        OrderCommandService secondCommand = commandProxy(service);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            // when
            Future<Boolean> first = workers.submit(() -> firstCommand.cancelExpiredOrder(fixture.orderId(), CUTOFF));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = workers.submit(() -> secondCommand.cancelExpiredOrder(fixture.orderId(), CUTOFF));
            assertThat(secondAttempted.await(5, TimeUnit.SECONDS)).isTrue();

            // then
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
            transactions.executeWithoutResult(status -> {
                Order order = orderRepository.findWithOrderItemsById(fixture.orderId()).orElseThrow();
                Inventory inventory = inventoryRepository.findById(fixture.inventoryId()).orElseThrow();
                assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
                assertThat(order.getOrderItems()).extracting(OrderItem::getStockState)
                        .containsOnly(StockState.RELEASED);
                assertQuantities(inventory, 10, 2, 8);
            });
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            cleanup(fixture);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 재고_해제가_실패하면_주문과_품목_변경도_실제_DB에서_롤백된다() {
        // given
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        FixtureIds fixture = transactions.execute(status -> saveReservedOrder(2));
        OrderCommandService command = commandProxy(
                new OrderService(orderRepository, mock(OrderConfirmationRepository.class)));

        try {
            // when
            // then
            assertThatThrownBy(() -> command.cancelExpiredOrder(fixture.orderId(), CUTOFF))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_STOCK));
            transactions.executeWithoutResult(status -> {
                Order order = orderRepository.findWithOrderItemsById(fixture.orderId()).orElseThrow();
                Inventory inventory = inventoryRepository.findById(fixture.inventoryId()).orElseThrow();
                assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
                assertThat(order.getOrderItems()).extracting(OrderItem::getStockState)
                        .containsOnly(StockState.RESERVED);
                assertQuantities(inventory, 10, 2, 8);
            });
        } finally {
            cleanup(fixture);
        }
    }

    private List<Long> candidates(int limit) {
        return orderRepository.findExpiredOrderIds(
                OrderStatus.PAYMENT_PENDING, CUTOFF, BLOCKED, PageRequest.of(0, limit));
    }

    private boolean eligible(Long orderId) {
        return orderRepository.existsExpiredOrder(orderId, OrderStatus.PAYMENT_PENDING, CUTOFF, BLOCKED);
    }

    private Order saveOrder(LocalDateTime createdAt, OrderStatus status) {
        User user = User.of("exp-" + token() + "@test.dev", "test-password", "만료 테스트", "01012345678");
        entityManager.persist(user);
        Order order = Order.of(user, "EXP-" + token(), "만료 테스트 주문", ActorType.USER, null, 3_000L,
                ShippingAddress.of("홍길동", "01012345678", "12345", "주문 당시 주소"));
        switch (status) {
            case PAID -> order.pay();
            case PAYMENT_FAILED -> order.failPayment();
            case CANCELED -> order.cancel();
            case PAYMENT_PENDING -> { }
        }
        entityManager.persist(order);
        entityManager.flush();
        // 생성 시각은 수정 불가 컬럼이므로 테스트 스키마의 해당 행만 직접 설정한다.
        entityManager.createNativeQuery("update " + TEST_SCHEMA + ".orders set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt).setParameter("id", order.getId()).executeUpdate();
        entityManager.refresh(order);
        return order;
    }

    private Payment savePayment(Long orderId, PaymentStatus status) {
        Payment payment = Payment.of(orderId, "payment-exp-" + token(), 3_000L);
        switch (status) {
            case READY -> { }
            case CONFIRM_PENDING -> payment.startConfirm();
            case PAID -> {
                payment.startConfirm();
                payment.complete(3_000L, CUTOFF, "TEST_CARD");
            }
            case FAILED -> payment.fail();
            case CANCELED -> {
                payment.startConfirm();
                payment.cancel(3_000L);
            }
        }
        entityManager.persist(payment);
        entityManager.flush();
        return payment;
    }

    private FixtureIds saveReservedOrder(int reserved) {
        Order order = saveOrder(CUTOFF.minusMinutes(1), OrderStatus.PAYMENT_PENDING);
        Category category = Category.of("만료-" + token(), true);
        Ingredient ingredient = Ingredient.of(category, "만료-" + token(), BaseUnit.G);
        Product product = Product.of("EXP-" + token(), ingredient, "테스트", "사과", null, 1_000L, 100, "1개");
        entityManager.persist(category);
        entityManager.persist(ingredient);
        entityManager.persist(product);
        OrderItem item = OrderItem.of(order, product, "구매 당시 사과", "1개", 1_000L, 3, 3_000L);
        entityManager.persist(item);
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(reserved);
        entityManager.persist(inventory);
        entityManager.flush();
        return new FixtureIds(order.getId(), inventory.getId(), product.getId(), ingredient.getId(),
                category.getId(), order.getUser().getId());
    }

    private OrderCommandService commandProxy(OrderService orderService) {
        OrderCommandService target = new OrderCommandService(
                mock(UserService.class), orderService, mock(QuoteService.class), mock(RefundService.class),
                mock(ProductService.class), new PaymentService(paymentRepository), mock(ChatRoomService.class),
                mock(DeliveryService.class), new InventoryService(inventoryRepository), mock(UserAddressService.class),
                new TransactionTemplate(transactionManager));
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        return (OrderCommandService) factory.getProxy();
    }

    private void cleanup(FixtureIds fixture) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("delete from OrderItem i where i.order.id = :id")
                    .setParameter("id", fixture.orderId()).executeUpdate();
            delete("Inventory", fixture.inventoryId());
            delete("Order", fixture.orderId());
            delete("Product", fixture.productId());
            delete("Ingredient", fixture.ingredientId());
            delete("Category", fixture.categoryId());
            delete("User", fixture.userId());
        });
    }

    private void delete(String entityName, Long id) {
        entityManager.createQuery("delete from " + entityName + " e where e.id = :id")
                .setParameter("id", id).executeUpdate();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void assertQuantities(Inventory inventory, int stock, int reserved, int available) {
        assertThat(inventory.getStockQuantity()).isEqualTo(stock);
        assertThat(inventory.getReservedQuantity()).isEqualTo(reserved);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(available);
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record FixtureIds(Long orderId, Long inventoryId, Long productId,
                              Long ingredientId, Long categoryId, Long userId) { }
}
