package com.server.zelo.global.seed;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 통합 부하 테스트용 재고 맞추기. zelo.seed.load-test.extra-stock을 0보다 크게 주면 앱을 켤 때 재고를 그 값으로 맞추고, 안 주면(기본 0) 아무것도 하지 않는다
// 더하지 않고 맞추는 이유: 서버가 여러 대이거나 재시작해도 재고가 쌓이지 않게 하기 위해서다
// fake LLM의 메뉴 계획은 문장과 관계없이 늘 같은 재료(FakeLlmGateway.planMenu: 김치·밥·계란·대파)를 돌려줘서 통합 테스트의 구매가 모두 이 재료 상품에 몰린다.
// 시드 재고는 상품당 20~199개라 금방 바닥나 주문이 재고 부족(PRODUCT_002)으로 실패한다 (계획서 3-5)
// 시드에서 재고 0으로 넣은 상품(검색의 inStock 조건용)은 품절 그대로 둔다. 테스트로 다 팔린 상품도 0이라 함께 건너뛰지만, 견적은 재고 있는 다른 상품을 고른다
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.seed.load-test.enabled", havingValue = "true")
public class LoadTestExtraStockSeedLoader {

    private static final List<String> FAKE_MENU_INGREDIENTS = List.of("김치", "밥", "계란", "대파");

    private static final String SET_STOCK = """
            update inventories i
            set stock_quantity = ?, available_quantity = ? - i.reserved_quantity, updated_at = now()
            from products p join ingredients ig on ig.id = p.ingredient_id
            where p.id = i.product_id and ig.name = any (?) and i.stock_quantity > 0""";

    private final JdbcTemplate jdbcTemplate;

    @Value("${zelo.seed.load-test.extra-stock:0}")
    private int extraStock;

    // 상품 시드(HIGHEST_PRECEDENCE)가 상품을 넣은 뒤에 돈다. 주문·결제·환불 시드도 순서 지정이 없어(LOWEST_PRECEDENCE) 그들과의 순서는 정해지지 않는다.
    // 가용 재고를 이 시점의 reserved_quantity를 빼서 계산하므로, 그 시드들이 먼저 돌든 나중에 돌든 재고 숫자가 맞는다
    @Order(Ordered.LOWEST_PRECEDENCE)
    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        if (extraStock <= 0) {
            return;
        }
        int updated = jdbcTemplate.update(SET_STOCK, extraStock, extraStock,
                FAKE_MENU_INGREDIENTS.toArray(String[]::new));
        log.info("부하 테스트 재고 맞추기 완료 ingredients={} products={} stock={}", FAKE_MENU_INGREDIENTS, updated, extraStock);
    }
}
