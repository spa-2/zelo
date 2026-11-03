package com.server.zelo.global.seed;

import com.server.zelo.domain.product.entity.ProductStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// 부하 테스트용 상품 10만 개. ZELO_SEED_LOAD_TEST_ENABLED=true 로 켰을 때만 돌고, 이미 10만 개면 건너뜀
// 기본 시드 상품을 복제해서 브랜드·이름·가격·판매량·재고만 바꿈. 재료·카테고리는 원본 그대로라 견적이 그대로 고를 수 있음
// 기본 시드(ApplicationRunner)가 끝난 뒤여야 해서 ApplicationReadyEvent에서 넣는다
// JPA saveAll은 IDENTITY라 한 줄씩 INSERT해서 느림. JdbcTemplate batchUpdate로 1,000개씩 끊어서 넣는다
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.seed.load-test.enabled", havingValue = "true")
public class LoadTestProductSeedLoader {

    private static final int TARGET_PRODUCT_COUNT = 100_000;
    private static final int CHUNK_SIZE = 1_000;
    // 이 시드로 만든 상품은 상품코드가 LT-로 시작해서 기본 시드 상품과 구분함
    private static final String CODE_PREFIX = "LT-";
    // 키워드(국산·유기농 등)랑 헷갈리지 않는 말만 이름 앞에 붙임
    private static final String[] NAME_PREFIXES = {"실속", "대용량", "소포장", "프리미엄", "산지직송", "알뜰", "데일리", "특가"};

    private static final String INSERT_PRODUCT = """
            insert into products (product_code, category_id, ingredient_id, brand, name, price, content_amount,
                                  unit_label, sales_count, status, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())""";
    private static final String INSERT_INVENTORY = """
            insert into inventories (product_id, stock_quantity, reserved_quantity, available_quantity, version,
                                     created_at, updated_at)
            values (?, ?, 0, ?, 0, now(), now())""";
    private static final String INSERT_KEYWORD = """
            insert into product_keywords (product_id, keyword, created_at)
            values (?, ?, now())""";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        List<SourceProduct> sources = readSources();
        if (sources.isEmpty()) {
            log.info("복제할 기본 시드 상품이 없어서 부하 테스트 상품 시드 건너뜀");
            return;
        }
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from products where product_code like 'LT-%'", Integer.class);
        int last = TARGET_PRODUCT_COUNT - sources.size();
        if (existing >= last) {
            log.info("부하 테스트 상품 시드가 이미 있어서 건너뜀");
            return;
        }
        Map<Long, List<String>> keywords = readKeywords();
        // 중간에 앱이 꺼져도 다음에 켜면 이어서 넣도록 이미 있는 LT- 다음 번호부터 시작
        for (int from = existing + 1; from <= last; from += CHUNK_SIZE) {
            List<NewProduct> chunk = buildChunk(sources, from, Math.min(from + CHUNK_SIZE - 1, last));
            transactionTemplate.executeWithoutResult(status -> saveChunk(chunk, keywords));
        }
        List.of("products", "inventories", "product_keywords").forEach(table -> jdbcTemplate.execute("analyze " + table));
        log.info("부하 테스트 상품 시드 저장 완료 added={}", last - existing);
    }

    private List<SourceProduct> readSources() {
        return jdbcTemplate.query("""
                        select id, category_id, ingredient_id, name, price, content_amount, unit_label
                        from products
                        where product_code not like 'LT-%'
                        order by id""",
                (rs, rowNum) -> new SourceProduct(rs.getLong("id"), rs.getLong("category_id"),
                        rs.getLong("ingredient_id"), rs.getString("name"), rs.getLong("price"),
                        rs.getInt("content_amount"), rs.getString("unit_label")));
    }

    // 원본 상품 키워드를 그대로 복사해서 맵지 않게·국내산 같은 조건이 원본과 같은 비율로 걸리게 함
    private Map<Long, List<String>> readKeywords() {
        Map<Long, List<String>> keywords = new HashMap<>();
        jdbcTemplate.query("select product_id, keyword from product_keywords where product_id in "
                        + "(select id from products where product_code not like 'LT-%')",
                rs -> {
                    keywords.computeIfAbsent(rs.getLong("product_id"), id -> new ArrayList<>())
                            .add(rs.getString("keyword"));
                });
        return keywords;
    }

    // 원본을 돌아가며 고르게 복제. 가격은 원본의 0.6~1.5배(10원 단위), 판매량은 소수 상품에 몰리게, 3%는 판매중지, 5%는 품절
    private List<NewProduct> buildChunk(List<SourceProduct> sources, int from, int to) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<NewProduct> chunk = new ArrayList<>(to - from + 1);
        for (int number = from; number <= to; number++) {
            SourceProduct source = sources.get(number % sources.size());
            long price = Math.max(100, Math.round(source.price() * (0.6 + random.nextDouble() * 0.9) / 10) * 10);
            ProductStatus status = random.nextDouble() < 0.03 ? ProductStatus.STOP_SALE : ProductStatus.ON_SALE;
            int stock = random.nextDouble() < 0.05 ? 0 : 20 + random.nextInt(180);
            chunk.add(new NewProduct(
                    CODE_PREFIX + String.format("%06d", number),
                    source,
                    "브랜드 " + (char) ('A' + number % 20),
                    NAME_PREFIXES[number % NAME_PREFIXES.length] + " " + source.name(),
                    price,
                    (int) (Math.pow(random.nextDouble(), 3) * 5000),
                    status,
                    stock));
        }
        return chunk;
    }

    // 상품을 넣고, 만들어진 ID를 상품코드로 다시 읽어서 재고·키워드를 넣는다
    private void saveChunk(List<NewProduct> chunk, Map<Long, List<String>> keywords) {
        jdbcTemplate.batchUpdate(INSERT_PRODUCT, chunk.stream()
                .map(product -> new Object[]{product.code(), product.source().categoryId(),
                        product.source().ingredientId(), product.brand(), product.name(), product.price(),
                        product.source().contentAmount(), product.source().unitLabel(), product.salesCount(),
                        product.status().name()})
                .toList());

        Map<String, Long> idsByCode = new HashMap<>();
        jdbcTemplate.query("select id, product_code from products where product_code between ? and ?",
                rs -> {
                    idsByCode.put(rs.getString("product_code"), rs.getLong("id"));
                },
                chunk.getFirst().code(), chunk.getLast().code());

        List<Object[]> inventories = new ArrayList<>(chunk.size());
        List<Object[]> productKeywords = new ArrayList<>();
        for (NewProduct product : chunk) {
            Long productId = idsByCode.get(product.code());
            inventories.add(new Object[]{productId, product.stock(), product.stock()});
            for (String keyword : keywords.getOrDefault(product.source().id(), List.of())) {
                productKeywords.add(new Object[]{productId, keyword});
            }
        }
        jdbcTemplate.batchUpdate(INSERT_INVENTORY, inventories);
        jdbcTemplate.batchUpdate(INSERT_KEYWORD, productKeywords);
    }

    private record SourceProduct(long id, long categoryId, long ingredientId, String name, long price,
                                 int contentAmount, String unitLabel) {
    }

    private record NewProduct(String code, SourceProduct source, String brand, String name, long price,
                              int salesCount, ProductStatus status, int stock) {
    }
}
