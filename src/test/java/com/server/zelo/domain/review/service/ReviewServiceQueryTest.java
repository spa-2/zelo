package com.server.zelo.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import com.server.zelo.domain.review.entity.ProductReviewKeyword;
import com.server.zelo.domain.review.entity.ProductReviewSummary;
import com.server.zelo.global.config.AuditingConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// 상품 수가 늘어도 SELECT가 늘지 않는지(N+1 없음)는 실제 DB에 쿼리를 날려 세어야 확인할 수 있다
@ActiveProfiles("test")
@Import({AuditingConfig.class, ReviewService.class})
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReviewServiceQueryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ReviewService reviewService;

    @Test
    void 상품이_여러_개여도_요약_1번_언급_수_1번_모두_2번만_조회한다() {
        // given: 리뷰가 있는 상품 3개와 없는 상품 1개
        Category category = Category.of("리뷰테스트-9000401", true);
        Ingredient ingredient = Ingredient.of(category, "리뷰테스트-9000401", BaseUnit.G);
        entityManager.persist(category);
        entityManager.persist(ingredient);
        Product first = persistProduct(ingredient, "REVIEW-TEST-9000401");
        Product second = persistProduct(ingredient, "REVIEW-TEST-9000402");
        Product third = persistProduct(ingredient, "REVIEW-TEST-9000403");
        Product noReview = persistProduct(ingredient, "REVIEW-TEST-9000404");
        for (Product product : List.of(first, second, third)) {
            entityManager.persist(ProductReviewSummary.of(product, new BigDecimal("4.5"), 10));
            entityManager.persist(ProductReviewKeyword.of(product, Keyword.NOT_SPICY, 3));
            entityManager.persist(ProductReviewKeyword.of(product, Keyword.LOW_SALT, 2));
        }
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        List<Long> productIds = List.of(first.getId(), second.getId(), third.getId(), noReview.getId());

        // when
        Map<Long, ProductReviewEvidence> result =
                reviewService.findReviewEvidences(productIds, Set.of(Keyword.NOT_SPICY, Keyword.LOW_SALT));

        // then
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(result).containsOnlyKeys(productIds);
        assertThat(result.get(first.getId()).keywordMentions())
                .containsExactlyInAnyOrderEntriesOf(Map.of(Keyword.NOT_SPICY, 3, Keyword.LOW_SALT, 2));
        assertThat(result.get(noReview.getId()).reviewCount()).isZero();
    }

    private Product persistProduct(Ingredient ingredient, String productCode) {
        Product product = Product.of(productCode, ingredient, "테스트브랜드", "김치", null, 10_000L, 1000, "1kg");
        entityManager.persist(product);
        return product;
    }
}
