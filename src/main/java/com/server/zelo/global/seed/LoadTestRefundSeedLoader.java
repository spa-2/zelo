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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 부하 테스트용 환불 대기 주문(결제 완료 + 출고 전 배송). ZELO_SEED_LOAD_TEST_ENABLED=true 로 켰을 때만 돈다
// 환불 테스트 한 번이면 상태가 바뀌어 다시 못 쓰므로, 켤 때마다 지난 데이터를 지우고 새로 넣는다 (재시작 = 초기화)
// 배송 대기는 10분이 지나면 배송 스케줄러가 출고시켜 환불이 막히므로, 배송 스케줄러를 끄거나 켠 뒤 바로 테스트한다
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.seed.load-test.enabled", havingValue = "true")
public class LoadTestRefundSeedLoader {

    private static final String PREFIX = "LT-REF-";
    // 다른 파트 VU와 같은 사용자를 쓰면 주문 상태가 서로 꼬이므로 팀에서 나눈 범위만 쓴다
    private static final int USER_FROM = 29_001;
    private static final int USER_COUNT = 400;
    // 한계점 찾기까지 한 번에 쓸 수 있는 양. k6가 앞에서부터 하나씩 꺼내 쓴다
    private static final int ORDER_COUNT = 10_000;

    private static final String INSERT_PAID_PAYMENT = """
            insert into payments (order_id, portone_id, amount, pg_amount, status, paid_at, payment_method_name,
                                  created_at, updated_at)
            values (?, ?, ?, ?, 'PAID', ?, 'TEST_CARD', ?, ?)""";
    private static final String INSERT_DELIVERY = """
            insert into deliveries (order_id, recipient_name, recipient_phone, zip_code, address, status, on_hold,
                                    created_at, updated_at)
            values (?, ?, ?, ?, ?, 'READY', false, ?, ?)""";

    private final LoadTestOrderSeedSupport loadTestOrderSeedSupport;

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        List<SeedProduct> products = loadTestOrderSeedSupport.readProducts();
        if (products.size() < LoadTestOrderSeedSupport.MIN_PRODUCT_COUNT) {
            // 상품 시드도 같은 시점에 돌아서 처음 켤 때는 상품이 아직 없을 수 있다. 상품 시드가 끝난 뒤 다시 켜면 들어간다
            log.info("부하 테스트 상품이 없어서 환불 시드 건너뜀 products={}", products.size());
            return;
        }
        loadTestOrderSeedSupport.deleteOrders(PREFIX);

        Map<Long, SeedAddress> addresses =
                loadTestOrderSeedSupport.readDefaultAddresses(USER_FROM, USER_FROM + USER_COUNT - 1);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        // 결제 시드와 다른 상품부터 골라 같은 재고 행에 두 시드가 겹치지 않게 한다
        ProductCursor cursor = new ProductCursor(products, products.size() / 2);
        loadTestOrderSeedSupport.saveInChunks(buildOrders(cursor), chunk -> saveChunk(chunk, addresses, now));

        loadTestOrderSeedSupport.analyze("orders", "order_items", "payments", "deliveries", "inventories");
        log.info("부하 테스트 환불 시드 저장 완료 orders={}", ORDER_COUNT);
    }

    private List<SeedOrder> buildOrders(ProductCursor cursor) {
        List<SeedOrder> orders = new ArrayList<>(ORDER_COUNT);
        for (int number = 1; number <= ORDER_COUNT; number++) {
            // 30%는 부분 환불을 할 수 있게 상품을 2~3개 담는다
            int itemCount = number % 10 < 3 ? 2 + number % 2 : 1 + number % 2;
            orders.add(new SeedOrder(LoadTestOrderSeedSupport.quoteId(PREFIX, number),
                    USER_FROM + number % USER_COUNT, "PAID", "DEDUCTED", cursor.items(itemCount, number)));
        }
        return orders;
    }

    private void saveChunk(List<SeedOrder> chunk, Map<Long, SeedAddress> addresses, Timestamp now) {
        Map<String, Long> orderIds = loadTestOrderSeedSupport.insertOrders(chunk, addresses, now);

        List<Object[]> payments = new ArrayList<>();
        List<Object[]> deliveries = new ArrayList<>();
        for (SeedOrder order : chunk) {
            Long orderId = orderIds.get(order.quoteId());
            payments.add(new Object[]{orderId, order.quoteId(), order.totalAmount(), order.totalAmount(),
                    now, now, now});
            SeedAddress address = addresses.get(order.userId());
            deliveries.add(new Object[]{orderId, address.recipientName(), address.recipientPhone(),
                    address.zipCode(), address.address(), now, now});
        }
        loadTestOrderSeedSupport.batch(INSERT_PAID_PAYMENT, payments);
        loadTestOrderSeedSupport.batch(INSERT_DELIVERY, deliveries);
    }
}
