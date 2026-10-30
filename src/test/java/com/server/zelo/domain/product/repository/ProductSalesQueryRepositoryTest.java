package com.server.zelo.domain.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.global.config.AuditingConfig;
import com.server.zelo.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 실행마다 별도 스키마를 만들어 실제 PostgreSQL에서 집계 쿼리를 확인함
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.sql.init.mode=never",
        "spring.jpa.show-sql=false"
})
@ActiveProfiles("test")
@Import({AuditingConfig.class, QuerydslConfig.class, ProductSalesQueryRepository.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductSalesQueryRepositoryTest {

    private static final String TEST_SCHEMA = "product_sales_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProductSalesQueryRepository productSalesQueryRepository;

    private User user;
    private Product kimchi;
    private Product egg;
    private Product tofu;

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> TEST_SCHEMA);
    }

    @BeforeEach
    void setUp() {
        Category category = Category.of(Category.of("국·반찬", true), "김치·젓갈");
        entityManager.persist(category.getParent());
        entityManager.persist(category);
        Ingredient ingredient = Ingredient.of(category, "김치", BaseUnit.G);
        entityManager.persist(ingredient);
        kimchi = persistProduct("P-1", ingredient, "백김치 1kg");
        egg = persistProduct("P-2", ingredient, "계란 10구");
        tofu = persistProduct("P-3", ingredient, "두부 300g");
        user = User.of("sales-" + UUID.randomUUID().toString().replace("-", "") + "@test.dev", "test-password", "판매 테스트", "01012345678");
        entityManager.persist(user);
    }

    @Test
    void 결제된_수량에서_취소한_수량을_빼고_많이_팔린_순으로_준다() {
        // given
        paidOrder(kimchi, 3, 0, LocalDateTime.now().minusDays(1));
        paidOrder(kimchi, 2, 1, LocalDateTime.now().minusDays(2));
        paidOrder(egg, 5, 0, LocalDateTime.now().minusDays(6));

        // when
        Map<Long, Long> top = productSalesQueryRepository.findTopSince(LocalDateTime.now().minusDays(7), 10);

        // then
        assertThat(top).containsExactly(Map.entry(egg.getId(), 5L), Map.entry(kimchi.getId(), 4L));
    }

    @Test
    void 기간_전_주문과_결제_전_주문과_전량_취소는_빠진다() {
        // given
        paidOrder(kimchi, 9, 0, LocalDateTime.now().minusDays(8));
        paidOrder(egg, 4, 4, LocalDateTime.now().minusDays(1));
        reservedOrder(tofu, 7);
        paidOrder(tofu, 1, 0, LocalDateTime.now().minusDays(1));

        // when
        Map<Long, Long> top = productSalesQueryRepository.findTopSince(LocalDateTime.now().minusDays(7), 10);

        // then
        assertThat(top).containsExactly(Map.entry(tofu.getId(), 1L));
    }

    @Test
    void 개수를_지킨다() {
        // given
        paidOrder(kimchi, 3, 0, LocalDateTime.now());
        paidOrder(egg, 2, 0, LocalDateTime.now());
        paidOrder(tofu, 1, 0, LocalDateTime.now());

        // when
        Map<Long, Long> top = productSalesQueryRepository.findTopSince(LocalDateTime.now().minusDays(7), 2);

        // then
        assertThat(top).containsOnlyKeys(kimchi.getId(), egg.getId());
    }

    private Product persistProduct(String code, Ingredient ingredient, String name) {
        Product product = Product.of(code, ingredient, "브랜드 A", name, null, 1000L, 1, "1개");
        entityManager.persist(product);
        return product;
    }

    private void paidOrder(Product product, int quantity, int canceled, LocalDateTime createdAt) {
        Order order = newOrder();
        OrderItem item = OrderItem.of(order, product, product.getName(), "1개", 1000L, quantity, 1000L * quantity);
        order.pay();
        item.deduct();
        if (canceled > 0) {
            item.cancelQuantity(canceled);
        }
        entityManager.persist(order);
        entityManager.persist(item);
        entityManager.flush();
        // created_at은 감사 기능이 지금 시각으로 넣어서 기간 테스트용으로 직접 바꿈
        entityManager.createQuery("update Order o set o.createdAt = :createdAt where o.id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", order.getId())
                .executeUpdate();
    }

    private void reservedOrder(Product product, int quantity) {
        Order order = newOrder();
        OrderItem item = OrderItem.of(order, product, product.getName(), "1개", 1000L, quantity, 1000L * quantity);
        entityManager.persist(order);
        entityManager.persist(item);
        entityManager.flush();
    }

    private Order newOrder() {
        return Order.of(user, "quote-" + UUID.randomUUID(), "판매 테스트 주문", ActorType.USER, null, 1000L,
                ShippingAddress.of("홍길동", "01012345678", "12345", "주문 당시 주소"));
    }
}
