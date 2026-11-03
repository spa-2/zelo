package com.server.zelo.global.seed;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// 결제·환불 시드가 같이 쓰는 주문 넣기·지우기. 주문 API를 거치지 않으므로 선점·차감 재고도 여기서 같이 맞춘다
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.seed.load-test.enabled", havingValue = "true")
public class LoadTestOrderSeedSupport {

    static final int MIN_PRODUCT_COUNT = 1_000;
    private static final int CHUNK_SIZE = 1_000;

    // 선점은 풀고, 차감된 수량 중 환불로 아직 복원되지 않은 만큼 실재고에 더해 상품 시드 때 재고로 되돌린다
    private static final String RESTORE_STOCK = """
            update inventories i
            set reserved_quantity  = i.reserved_quantity - s.reserved,
                stock_quantity     = i.stock_quantity + s.deducted,
                available_quantity = i.available_quantity + s.reserved + s.deducted,
                version            = i.version + 1,
                updated_at         = now()
            from (select oi.product_id,
                         coalesce(sum(oi.quantity) filter (where oi.stock_state = 'RESERVED'), 0) as reserved,
                         coalesce(sum(oi.quantity - oi.canceled_quantity)
                                  filter (where oi.stock_state = 'DEDUCTED'), 0) as deducted
                  from order_items oi
                  where oi.order_id in (select id from orders where quote_id like ?)
                  group by oi.product_id) s
            where i.product_id = s.product_id""";
    private static final String SEED_ORDER_IDS = "select id from orders where quote_id like ?";
    private static final List<String> DELETE_ORDERS = List.of(
            "delete from refund_items where refund_id in (select r.id from refunds r join payments p on p.id = r.payment_id "
                    + "where p.order_id in (" + SEED_ORDER_IDS + "))",
            "delete from refunds where payment_id in (select id from payments where order_id in (" + SEED_ORDER_IDS + "))",
            "delete from deliveries where order_id in (" + SEED_ORDER_IDS + ")",
            "delete from payments where order_id in (" + SEED_ORDER_IDS + ")",
            "delete from order_audit_logs where order_id in (" + SEED_ORDER_IDS + ")",
            "delete from order_items where order_id in (" + SEED_ORDER_IDS + ")",
            "delete from orders where quote_id like ?");

    private static final String INSERT_ORDER = """
            insert into orders (user_id, quote_id, order_name, requester_type, status, total_amount,
                                recipient_name, recipient_phone, zip_code, address, created_at, updated_at)
            values (?, ?, ?, 'USER', ?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String INSERT_ORDER_ITEM = """
            insert into order_items (order_id, product_id, product_name, product_unit, unit_price, quantity,
                                     line_amount, stock_state, canceled_quantity, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)""";
    // 결제 대기는 선점(+q), 결제 완료는 선점 후 차감한 결과(실재고 −q)라 가용 수량은 둘 다 q만큼 준다
    private static final String APPLY_STOCK = """
            update inventories
            set reserved_quantity  = reserved_quantity + ?,
                stock_quantity     = stock_quantity - ?,
                available_quantity = available_quantity - ?,
                version            = version + 1,
                updated_at         = now()
            where product_id = ?""";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public List<SeedProduct> readProducts() {
        return jdbcTemplate.query("""
                        select p.id, p.name, p.price, p.content_amount, p.unit_label
                        from products p
                        join inventories i on i.product_id = p.id
                        where p.product_code like 'LT-%' and p.status = 'ON_SALE' and i.available_quantity >= 10
                        order by p.id""",
                (rs, rowNum) -> new SeedProduct(rs.getLong("id"), rs.getString("name"), rs.getLong("price"),
                        rs.getInt("content_amount") + rs.getString("unit_label")));
    }

    // 주문 배송지는 사용자 시드의 기본 배송지를 그대로 복사한다
    public Map<Long, SeedAddress> readDefaultAddresses(int fromUserId, int toUserId) {
        Map<Long, SeedAddress> addresses = new HashMap<>();
        jdbcTemplate.query("""
                        select user_id, recipient_name, recipient_phone, zip_code, address
                        from user_addresses
                        where is_default and user_id between ? and ?""",
                rs -> {
                    addresses.put(rs.getLong("user_id"), new SeedAddress(rs.getString("recipient_name"),
                            rs.getString("recipient_phone"), rs.getString("zip_code"), rs.getString("address")));
                },
                fromUserId, toUserId);
        return addresses;
    }

    // 지난 테스트에서 상태가 바뀐 주문은 다시 못 쓰므로, 잡고 있던 재고를 되돌린 뒤 지운다
    public void deleteOrders(String prefix) {
        String pattern = prefix + "%";
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(RESTORE_STOCK, pattern);
            DELETE_ORDERS.forEach(sql -> jdbcTemplate.update(sql, pattern));
        });
    }

    public <T> void saveInChunks(List<T> rows, Consumer<List<T>> saver) {
        for (int from = 0; from < rows.size(); from += CHUNK_SIZE) {
            List<T> chunk = rows.subList(from, Math.min(from + CHUNK_SIZE, rows.size()));
            transactionTemplate.executeWithoutResult(status -> saver.accept(chunk));
        }
    }

    // 주문·주문 상품을 넣고 재고를 맞춘 뒤, 결제·배송을 붙일 수 있게 quote_id별 주문 ID를 돌려준다
    public Map<String, Long> insertOrders(List<SeedOrder> orders, Map<Long, SeedAddress> addresses, Timestamp now) {
        jdbcTemplate.batchUpdate(INSERT_ORDER, orders.stream()
                .map(order -> {
                    SeedAddress address = addresses.get(order.userId());
                    return new Object[]{order.userId(), order.quoteId(), order.orderName(), order.orderStatus(),
                            order.totalAmount(), address.recipientName(), address.recipientPhone(),
                            address.zipCode(), address.address(), now, now};
                })
                .toList());
        Map<String, Long> orderIds = readOrderIds(orders);

        List<Object[]> items = new ArrayList<>();
        List<Object[]> stocks = new ArrayList<>();
        for (SeedOrder order : orders) {
            for (SeedItem item : order.items()) {
                SeedProduct product = item.product();
                items.add(new Object[]{orderIds.get(order.quoteId()), product.id(), product.name(), product.unit(),
                        product.price(), item.quantity(), item.amount(), order.stockState(), now, now});
                int reserved = "RESERVED".equals(order.stockState()) ? item.quantity() : 0;
                int deducted = "DEDUCTED".equals(order.stockState()) ? item.quantity() : 0;
                stocks.add(new Object[]{reserved, deducted, reserved + deducted, product.id()});
            }
        }
        batch(INSERT_ORDER_ITEM, items);
        batch(APPLY_STOCK, stocks);
        return orderIds;
    }

    public void batch(String sql, List<Object[]> args) {
        if (!args.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, args);
        }
    }

    public void analyze(String... tables) {
        for (String table : tables) {
            jdbcTemplate.execute("analyze " + table);
        }
    }

    static String quoteId(String prefix, int number) {
        return prefix + String.format("%07d", number);
    }

    private Map<String, Long> readOrderIds(List<SeedOrder> orders) {
        Map<String, Long> orderIds = new HashMap<>();
        jdbcTemplate.query("select id, quote_id from orders where quote_id between ? and ?",
                rs -> {
                    orderIds.put(rs.getString("quote_id"), rs.getLong("id"));
                },
                orders.getFirst().quoteId(), orders.getLast().quoteId());
        return orderIds;
    }

    // 주문 상품을 상품 전체에 돌아가며 나눠 같은 재고 행에 충돌(@Version)이 몰리지 않게 한다
    static final class ProductCursor {

        private final List<SeedProduct> products;
        private int index;

        ProductCursor(List<SeedProduct> products, int start) {
            this.products = products;
            this.index = start;
        }

        List<SeedItem> items(int itemCount, int number) {
            List<SeedItem> items = new ArrayList<>(itemCount);
            for (int sequence = 0; sequence < itemCount; sequence++) {
                items.add(new SeedItem(products.get(index++ % products.size()), 1 + (number + sequence) % 2));
            }
            return items;
        }
    }

    record SeedProduct(long id, String name, long price, String unit) {
    }

    record SeedAddress(String recipientName, String recipientPhone, String zipCode, String address) {
    }

    record SeedItem(SeedProduct product, int quantity) {

        long amount() {
            return product.price() * quantity;
        }
    }

    record SeedOrder(String quoteId, long userId, String orderStatus, String stockState, List<SeedItem> items) {

        long totalAmount() {
            return items.stream().mapToLong(SeedItem::amount).sum();
        }

        String orderName() {
            String firstName = items.getFirst().product().name();
            return items.size() == 1 ? firstName : firstName + " 외 " + (items.size() - 1) + "건";
        }
    }
}
