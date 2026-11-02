package com.server.zelo.support;

import com.server.zelo.domain.delivery.entity.Delivery;
import com.server.zelo.domain.delivery.repository.DeliveryRepository;
import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.repository.CategoryRepository;
import com.server.zelo.domain.product.repository.IngredientRepository;
import com.server.zelo.domain.product.repository.ProductRepository;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// 동시성 테스트는 여러 스레드가 커밋된 데이터를 함께 봐야 해서 @Transactional 롤백 대신 만든 데이터를 직접 지운다
@TestComponent
@RequiredArgsConstructor
public class Concurrency {

    public static final long UNIT_PRICE = 3_000L;

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final DeliveryRepository deliveryRepository;
    private final InventoryRepository inventoryRepository;
    private final TransactionTemplate transactionTemplate;
    private final IngredientRepository ingredientRepository;

    public record Stock(Long categoryId, Long ingredientId, Long productId, Long inventoryId) {
    }

    public record SavedOrder(Long userId, Long orderId, String portoneId, Stock stock) {
    }

    public Stock saveStock(int stockQuantity) {
        return transactionTemplate.execute(status -> {
            Inventory inventory = inventoryRepository.save(Inventory.of(saveProduct(), stockQuantity));
            return toStock(inventory);
        });
    }

    // 결제창에서 결제를 마친 직후: 주문은 결제 대기, 재고 1개 선점, 결제는 READY (가짜 포트원은 PAID로 응답)
    public SavedOrder saveOrderWithReadyPayment() {
        return transactionTemplate.execute(status -> {
            Inventory inventory = Inventory.of(saveProduct(), 10);
            inventory.reserve(1);
            inventoryRepository.save(inventory);
            Order order = saveOrder(inventory.getProduct());
            Payment payment = paymentRepository.save(Payment.of(order.getId(), "payment-" + token(), UNIT_PRICE));
            return new SavedOrder(order.getUser().getId(), order.getId(), payment.getPortoneId(), toStock(inventory));
        });
    }

    // 결제창에서 실패한 주문: 주문은 결제 대기로 남고 결제 하나가 FAILED
    public SavedOrder saveOrderWithFailedPayment() {
        return transactionTemplate.execute(status -> {
            Inventory inventory = Inventory.of(saveProduct(), 10);
            inventory.reserve(1);
            inventoryRepository.save(inventory);
            Order order = saveOrder(inventory.getProduct());
            Payment payment = Payment.of(order.getId(), "payment-" + token(), UNIT_PRICE);
            payment.fail("테스트 결제 실패");
            paymentRepository.save(payment);
            return new SavedOrder(order.getUser().getId(), order.getId(), payment.getPortoneId(), toStock(inventory));
        });
    }

    // 결제와 재고 차감까지 끝나고 출고 전인 주문
    public SavedOrder savePaidOrder() {
        return transactionTemplate.execute(status -> {
            Inventory inventory = Inventory.of(saveProduct(), 10);
            inventory.reserve(1);
            inventory.deduct(1);
            inventoryRepository.save(inventory);
            Order order = saveOrder(inventory.getProduct());
            order.pay();
            order.getOrderItems().forEach(OrderItem::deduct);
            Payment payment = Payment.of(order.getId(), "payment-" + token(), UNIT_PRICE);
            payment.startConfirm();
            payment.complete(UNIT_PRICE, LocalDateTime.now(), "TEST_CARD");
            paymentRepository.save(payment);
            ShippingAddress address = order.getShippingAddress();
            deliveryRepository.save(Delivery.of(order.getId(), address.getRecipientName(), address.getRecipientPhone(),
                    address.getZipCode(), address.getAddress(), null));
            return new SavedOrder(order.getUser().getId(), order.getId(), payment.getPortoneId(), toStock(inventory));
        });
    }

    // 모든 스레드를 준비시킨 뒤 같은 순간에 출발시키고, 예외 없이 끝난 요청 수를 돌려준다
    public int runConcurrently(int threadCount, Runnable task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    // 락이나 상태 검증에 막힌 요청은 성공 수에서 빠진다
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        return successCount.get();
    }

    // 외래 키 순서대로 지운다. 주문 이력과 배송은 주문 ID만 들고 있어 주문보다 먼저 지운다
    public void delete(SavedOrder savedOrder) {
        Long orderId = savedOrder.orderId();
        jdbcTemplate.update("""
                delete from refund_items where refund_id in
                    (select r.id from refunds r join payments p on r.payment_id = p.id where p.order_id = ?)
                """, orderId);
        jdbcTemplate.update("delete from refunds where payment_id in (select id from payments where order_id = ?)", orderId);
        jdbcTemplate.update("delete from payments where order_id = ?", orderId);
        jdbcTemplate.update("delete from deliveries where order_id = ?", orderId);
        jdbcTemplate.update("delete from order_audit_logs where order_id = ?", orderId);
        jdbcTemplate.update("delete from order_items where order_id = ?", orderId);
        jdbcTemplate.update("delete from orders where id = ?", orderId);
        userRepository.deleteById(savedOrder.userId());
        delete(savedOrder.stock());
    }

    public void delete(Stock stock) {
        inventoryRepository.deleteById(stock.inventoryId());
        productRepository.deleteById(stock.productId());
        ingredientRepository.deleteById(stock.ingredientId());
        categoryRepository.deleteById(stock.categoryId());
    }

    private Product saveProduct() {
        Category category = categoryRepository.save(Category.of("test-" + token(), false));
        Ingredient ingredient = ingredientRepository.save(Ingredient.of(category, "test-" + token(), BaseUnit.G));
        return productRepository.save(
                Product.of("TEST-" + token(), ingredient, "테스트", "동시성 테스트 상품", null, UNIT_PRICE, 100, "1개"));
    }

    // 상품 1개짜리 주문. OrderItem.of가 주문에 상품을 넣고, 주문을 저장할 때 함께 저장된다
    private Order saveOrder(Product product) {
        User user = userRepository.save(
                User.of("concurrency-" + token() + "@test.dev", "test-password", "동시성 테스트", "01012345678"));
        Order order = Order.of(user, "CONCURRENCY-" + token(), "동시성 테스트 주문", ActorType.USER, null, UNIT_PRICE,
                ShippingAddress.of("홍길동", "01012345678", "12345", "테스트 주소"));
        OrderItem.of(order, product, product.getName(), "1개", UNIT_PRICE, 1, UNIT_PRICE);
        return orderRepository.save(order);
    }

    private Stock toStock(Inventory inventory) {
        Product product = inventory.getProduct();
        Ingredient ingredient = product.getIngredient();
        return new Stock(ingredient.getCategory().getId(), ingredient.getId(), product.getId(), inventory.getId());
    }

    private static String token() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}