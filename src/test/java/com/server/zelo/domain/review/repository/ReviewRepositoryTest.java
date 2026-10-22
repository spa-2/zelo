package com.server.zelo.domain.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.review.entity.ProductReviewKeyword;
import com.server.zelo.domain.review.entity.ProductReviewSummary;
import com.server.zelo.domain.review.entity.Review;
import com.server.zelo.global.config.AuditingConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

// smallint·decimal 매핑과 유니크 제약은 PostgreSQL에서 확인해야 해서 실제 DB를 쓰고, 시각 컬럼을 위해 Auditing 설정을 띄운다
@DataJpaTest
@ActiveProfiles("test")
@Import(AuditingConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReviewRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProductReviewKeywordRepository productReviewKeywordRepository;

    @Autowired
    private ProductReviewSummaryRepository productReviewSummaryRepository;

    private Product baek;
    private Product pogi;
    private Product yeolmu;

    @BeforeEach
    void setUp() {
        // 카테고리·재료 이름과 상품 코드는 유니크라서, 로컬 DB에 있는 데이터와 겹치지 않을 이름을 쓴다
        Category category = Category.of("리뷰테스트-9000301", true);
        Ingredient ingredient = Ingredient.of(category, "리뷰테스트-9000301", BaseUnit.G);
        entityManager.persist(category);
        entityManager.persist(ingredient);
        baek = persistProduct(ingredient, "REVIEW-TEST-9000301", "백김치");
        pogi = persistProduct(ingredient, "REVIEW-TEST-9000302", "포기김치");
        yeolmu = persistProduct(ingredient, "REVIEW-TEST-9000303", "열무김치");
    }

    @Test
    void 리뷰를_저장하면_평점과_생성_시각이_채워지고_다시_읽을_수_있다() {
        // given
        Review review = Review.of(9_000_301L, baek, 5, "하나도 안 매워서 애들이 잘 먹어요");
        entityManager.persist(review);
        entityManager.flush();
        entityManager.clear();

        // when
        Review found = entityManager.find(Review.class, review.getId());

        // then
        assertThat(found.getUserId()).isEqualTo(9_000_301L);
        assertThat(found.getProduct().getId()).isEqualTo(baek.getId());
        assertThat(found.getRating()).isEqualTo(5);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 요약은_상품_ID를_PK로_저장되고_평균_평점이_소수_둘째_자리로_읽힌다() {
        // given
        productReviewSummaryRepository.saveAndFlush(ProductReviewSummary.of(baek, new BigDecimal("4.5"), 23));
        entityManager.clear();

        // when
        ProductReviewSummary found = productReviewSummaryRepository.findById(baek.getId()).orElseThrow();

        // then
        assertThat(found.getProductId()).isEqualTo(baek.getId());
        assertThat(found.getAvgRating()).isEqualTo(new BigDecimal("4.50"));
        assertThat(found.getReviewCount()).isEqualTo(23);
        assertThat(found.getContent()).isNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void 요청한_상품과_키워드의_언급_수만_가져온다() {
        // given
        productReviewKeywordRepository.saveAll(List.of(
                ProductReviewKeyword.of(baek, Keyword.NOT_SPICY, 15),
                ProductReviewKeyword.of(baek, Keyword.LOW_SALT, 6),
                ProductReviewKeyword.of(pogi, Keyword.NOT_SPICY, 1),
                ProductReviewKeyword.of(yeolmu, Keyword.NOT_SPICY, 2)));
        entityManager.flush();
        entityManager.clear();

        // when
        List<ProductReviewKeyword> result = productReviewKeywordRepository
                .findAllByProductIdInAndKeywordIn(List.of(baek.getId(), pogi.getId()), Set.of(Keyword.NOT_SPICY));

        // then
        assertThat(result)
                .extracting(keyword -> keyword.getProduct().getId(), ProductReviewKeyword::getKeyword,
                        ProductReviewKeyword::getMentionCount)
                .containsExactlyInAnyOrder(
                        tuple(baek.getId(), Keyword.NOT_SPICY, 15),
                        tuple(pogi.getId(), Keyword.NOT_SPICY, 1));
    }

    @Test
    void 언급_수를_조회해도_상품_ID만_읽으면_상품을_추가로_조회하지_않는다() {
        // given
        productReviewKeywordRepository.saveAndFlush(ProductReviewKeyword.of(baek, Keyword.NOT_SPICY, 15));
        entityManager.clear();

        // when
        ProductReviewKeyword found = productReviewKeywordRepository
                .findAllByProductIdInAndKeywordIn(List.of(baek.getId()), Set.of(Keyword.NOT_SPICY)).get(0);
        Long productId = found.getProduct().getId();

        // then: 서비스가 상품 ID로 묶을 때 상품마다 SELECT가 나가면 N+1이 된다
        assertThat(productId).isEqualTo(baek.getId());
        assertThat(Hibernate.isInitialized(found.getProduct())).isFalse();
    }

    @Test
    void 같은_상품에_같은_키워드를_두_번_저장할_수_없다() {
        // given
        productReviewKeywordRepository.saveAndFlush(ProductReviewKeyword.of(baek, Keyword.NOT_SPICY, 15));

        // when & then
        assertThatThrownBy(() -> productReviewKeywordRepository
                .saveAndFlush(ProductReviewKeyword.of(baek, Keyword.NOT_SPICY, 3)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Product persistProduct(Ingredient ingredient, String productCode, String name) {
        Product product = Product.of(productCode, ingredient, name, null, 10_000L, 1000, "1kg");
        entityManager.persist(product);
        return product;
    }
}
