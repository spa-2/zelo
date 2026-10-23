package com.server.zelo.domain.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.global.config.AuditingConfig;
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

    // UPDATE 쿼리는 영속성 컨텍스트를 안 거쳐서 비우고 다시 읽어야 바뀐 값이 보임
    private int salesCount() {
        entityManager.clear();
        return entityManager.find(Product.class, product.getId()).getSalesCount();
    }
}