package com.server.zelo.global.seed;

import com.server.zelo.global.seed.LoadTestOrderSeedSupport.ProductCursor;
import com.server.zelo.global.seed.LoadTestOrderSeedSupport.SeedAddress;
import com.server.zelo.global.seed.LoadTestOrderSeedSupport.SeedOrder;
import com.server.zelo.global.seed.LoadTestOrderSeedSupport.SeedProduct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 부하 테스트용 결제 대기 주문. ZELO_SEED_LOAD_TEST_ENABLED=true 로 켰을 때만 돈다
// 결제 테스트 한 번이면 상태가 바뀌어 다시 못 쓰므로, 켤 때마다 지난 데이터를 지우고 새로 넣는다 (재시작 = 초기화)
// 결제 대기 주문은 30분이 지나면 만료 스케줄러가 취소하고 재결제도 막히므로 켠 뒤 바로 테스트한다
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.seed.load-test.enabled", havingValue = "true")
public class LoadTestPaymentSeedLoader {

    private static final String PREFIX = "LT-PAY-";
    // 다른 파트 VU와 같은 사용자를 쓰면 주문 상태가 서로 꼬이므로 팀에서 나눈 범위만 쓴다
    private static final int USER_FROM = 25_001;
    private static final int USER_COUNT = 4_000;
    // 한계점 찾기까지 한 번에 쓸 수 있는 양. k6가 앞에서부터 하나씩 꺼내 쓴다
    private static final int ORDER_COUNT = 100_000;

    private static final String INSERT_PAYMENT_METHOD = """
            insert into payment_methods (user_id, type, billing_key, display_name, created_at, updated_at)
            values (?, 'CARD', ?, '테스트카드 1234', now(), now())
            on conflict (billing_key) do nothing""";
    private static final String INSERT_READY_PAYMENT = """
            insert into payments (order_id, portone_id, amount, status, created_at, updated_at)
            values (?, ?, ?, 'READY', ?, ?)""";
    private static final String INSERT_FAILED_PAYMENT = """
            insert into payments (order_id, portone_id, amount, status, failure_reason, created_at, updated_at)
            values (?, ?, ?, 'FAILED', '부하 테스트 결제 실패', ?, ?)""";

    private final LoadTestOrderSeedSupport loadTestOrderSeedSupport;

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        List<SeedProduct> products = loadTestOrderSeedSupport.readProducts();
        if (products.size() < LoadTestOrderSeedSupport.MIN_PRODUCT_COUNT) {
            // 상품 시드도 같은 시점에 돌아서 처음 켤 때는 상품이 아직 없을 수 있다. 상품 시드가 끝난 뒤 다시 켜면 들어간다
            log.info("부하 테스트 상품이 없어서 결제 시드 건너뜀 products={}", products.size());
            return;
        }
        loadTestOrderSeedSupport.deleteOrders(PREFIX);
        insertPaymentMethods();

        Map<Long, SeedAddress> addresses =
                loadTestOrderSeedSupport.readDefaultAddresses(USER_FROM, USER_FROM + USER_COUNT - 1);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        loadTestOrderSeedSupport.saveInChunks(buildOrders(new ProductCursor(products, 0)),
                chunk -> saveChunk(chunk, addresses, now));

        loadTestOrderSeedSupport.analyze("orders", "order_items", "payments", "inventories");
        log.info("부하 테스트 결제 시드 저장 완료 orders={}", ORDER_COUNT);
    }

    // 가짜 포트원은 빌링키 끝 숫자를 발급 고객으로 보므로 사용자 id로 끝나게 만든다
    private void insertPaymentMethods() {
        loadTestOrderSeedSupport.batch(INSERT_PAYMENT_METHOD, IntStream.range(USER_FROM, USER_FROM + USER_COUNT)
                .mapToObj(userId -> new Object[]{userId, "fake-billing-key-" + userId})
                .toList());
    }

    private List<PaymentOrder> buildOrders(ProductCursor cursor) {
        List<PaymentOrder> orders = new ArrayList<>(ORDER_COUNT);
        for (int number = 1; number <= ORDER_COUNT; number++) {
            SeedOrder order = new SeedOrder(LoadTestOrderSeedSupport.quoteId(PREFIX, number),
                    USER_FROM + number % USER_COUNT, "PAYMENT_PENDING", "RESERVED",
                    cursor.items(1 + number % 2, number));
            // 결제 실패 후 재결제 비율 15%
            orders.add(new PaymentOrder(order, number % 20 >= 17));
        }
        return orders;
    }

    private void saveChunk(List<PaymentOrder> chunk, Map<Long, SeedAddress> addresses, Timestamp now) {
        Map<String, Long> orderIds = loadTestOrderSeedSupport.insertOrders(
                chunk.stream().map(PaymentOrder::order).toList(), addresses, now);

        List<Object[]> readyPayments = new ArrayList<>();
        List<Object[]> failedPayments = new ArrayList<>();
        for (PaymentOrder paymentOrder : chunk) {
            SeedOrder order = paymentOrder.order();
            Object[] payment = {orderIds.get(order.quoteId()), order.quoteId(), order.totalAmount(), now, now};
            if (paymentOrder.retry()) {
                failedPayments.add(payment);
            } else {
                readyPayments.add(payment);
            }
        }
        loadTestOrderSeedSupport.batch(INSERT_READY_PAYMENT, readyPayments);
        loadTestOrderSeedSupport.batch(INSERT_FAILED_PAYMENT, failedPayments);
    }

    private record PaymentOrder(SeedOrder order, boolean retry) {
    }
}
