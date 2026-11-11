package com.server.zelo.domain.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductKeyword;
import com.server.zelo.global.config.AuditingConfig;
import java.util.List;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import(AuditingConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProductRepository productRepository;

    private Product product;

    @BeforeEach
    void setUp() {
        // 이름이랑 상품 코드가 유니크라 로컬 시드랑 안 겹치게
        Category category = Category.of("판매량테스트-9000401", true);
        Ingredient ingredient = Ingredient.of(category, "판매량테스트-9000401", BaseUnit.G);
        product = Product.of("SALES-TEST-9000401", ingredient, "브랜드 A", "백김치 1kg", null, 8900L, 1000, "1kg");
        entityManager.persist(category);
        entityManager.persist(ingredient);
        entityManager.persist(product);
        entityManager.flush();
    }

    @Test
    void 판매량을_수량만큼_올린다() {
        // when
        int updated = productRepository.increaseSalesCount(product.getId(), 3);

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(salesCount()).isEqualTo(3);
    }

    @Test
    void 판매량을_수량만큼_내린다() {
        // given
        productRepository.increaseSalesCount(product.getId(), 3);

        // when
        int updated = productRepository.decreaseSalesCount(product.getId(), 2);

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(salesCount()).isEqualTo(1);
    }

    @Test
    void 판매량보다_많이_내리면_바꾸지_않는다() {
        // given
        productRepository.increaseSalesCount(product.getId(), 1);

        // when
        int updated = productRepository.decreaseSalesCount(product.getId(), 2);

        // then
        assertThat(updated).isZero();
        assertThat(salesCount()).isEqualTo(1);
    }

    @Test
    void 견적_후보는_조건_키워드_묶음마다_판매량_상위와_총액_하위만_고른다() {
        // given: 같은 재료 상품 4개. 맵지 않은 상품은 따로 한 묶음
        Category category = Category.of("후보테스트-9000402", true);
        Ingredient ingredient = Ingredient.of(category, "후보테스트-9000402", BaseUnit.G);
        entityManager.persist(category);
        entityManager.persist(ingredient);
        Product bestSeller = save("CAND-9000402-A", ingredient, 1000L, 100, 50);
        Product cheapest = save("CAND-9000402-B", ingredient, 300L, 100, 1);
        save("CAND-9000402-C", ingredient, 900L, 100, 10);
        Product notSpicy = save("CAND-9000402-D", ingredient, 5000L, 100, 5);
        entityManager.persist(ProductKeyword.of(notSpicy, Keyword.NOT_SPICY));
        entityManager.flush();

        // when
        List<Long> ids = productRepository.findQuoteCandidateIds("{\"후보테스트-9000402\": 100}", "NOT_SPICY", 1);

        // then: 판매량도 총액도 1등이 아닌 C는 빠진다
        assertThat(ids).containsExactlyInAnyOrder(bestSeller.getId(), cheapest.getId(), notSpicy.getId());
    }

    @Test
    void 견적_후보의_싼_순서는_필요한_양을_샀을_때_총액으로_본다() {
        // given: 150g이 필요하면 1kg 2,500원(1개)보다 100g 300원(2개, 600원)이 싸다. g당 가격은 1kg짜리가 더 싸다
        Category category = Category.of("후보테스트-9000403", true);
        Ingredient ingredient = Ingredient.of(category, "후보테스트-9000403", BaseUnit.G);
        entityManager.persist(category);
        entityManager.persist(ingredient);
        Product bestSeller = save("CAND-9000403-A", ingredient, 9990L, 100, 100);
        save("CAND-9000403-B", ingredient, 2500L, 1000, 1);
        Product smallPack = save("CAND-9000403-C", ingredient, 300L, 100, 2);
        entityManager.flush();

        // when
        List<Long> ids = productRepository.findQuoteCandidateIds("{\"후보테스트-9000403\": 150}", "", 1);

        // then
        assertThat(ids).containsExactlyInAnyOrder(bestSeller.getId(), smallPack.getId());
    }

    private Product save(String code, Ingredient ingredient, Long price, int contentAmount, int salesCount) {
        Product saved = Product.of(code, ingredient, "브랜드", code, null, price, contentAmount, contentAmount + "g");
        entityManager.persist(saved);
        entityManager.flush();
        productRepository.increaseSalesCount(saved.getId(), salesCount);
        return saved;
    }

    // UPDATE 쿼리는 영속성 컨텍스트를 안 거쳐서 비우고 다시 읽어야 바뀐 값이 보임
    private int salesCount() {
        entityManager.clear();
        return entityManager.find(Product.class, product.getId()).getSalesCount();
    }
}