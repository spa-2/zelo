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
import org.springframework.data.domain.Limit;
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
    private ReviewRepository reviewRepository;

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

    @Test
    void 요약_대상은_AI_요약이_없고_리뷰가_기준_이상인_상품만_고른다() {
        // given
        ProductReviewSummary noContent = ProductReviewSummary.of(baek, new BigDecimal("4.5"), 23);
        ProductReviewSummary hasContent = ProductReviewSummary.of(pogi, new BigDecimal("4.7"), 30);
        hasContent.updateContent("얼큰하다는 평이 많아요");
        ProductReviewSummary fewReviews = ProductReviewSummary.of(yeolmu, new BigDecimal("4.0"), 3);
        productReviewSummaryRepository.saveAll(List.of(noContent, hasContent, fewReviews));
        entityManager.flush();
        entityManager.clear();

        // when
        List<ProductReviewSummary> result =
                productReviewSummaryRepository.findAllByContentIsNullAndReviewCountGreaterThanEqual(5);

        // then: 로컬 DB에 다른 요약이 있을 수 있어, 이 테스트가 넣은 상품 중에서만 확인한다
        assertThat(result).extracting(ProductReviewSummary::getProductId)
                .contains(baek.getId())
                .doesNotContain(pogi.getId(), yeolmu.getId());
    }

    @Test
    void 최근_리뷰를_최신순으로_개수만큼_상품과_함께_가져온다() {
        // given
        Review first = Review.of(9_000_301L, baek, 5, "첫 리뷰");
        Review second = Review.of(9_000_302L, baek, 4, "두 번째 리뷰");
        Review third = Review.of(9_000_303L, baek, 3, "세 번째 리뷰");
        Review other = Review.of(9_000_304L, pogi, 5, "다른 상품 리뷰");
        List.of(first, second, third, other).forEach(entityManager::persist);
        entityManager.flush();
        entityManager.clear();

        // when
        List<Review> result = reviewRepository.findByProductIdOrderByIdDesc(baek.getId(), Limit.of(2));

        // then: 상품 이름을 트랜잭션 밖에서도 읽을 수 있도록 상품이 이미 채워져 있어야 한다
        assertThat(result).extracting(Review::getId).containsExactly(third.getId(), second.getId());
        assertThat(result).allSatisfy(review -> assertThat(Hibernate.isInitialized(review.getProduct())).isTrue());
        assertThat(result.get(0).getProduct().getName()).isEqualTo("백김치");
    }

    @Test
    void AI_요약을_저장하면_다시_읽을_수_있다() {
        // given
        ProductReviewSummary summary = productReviewSummaryRepository
                .saveAndFlush(ProductReviewSummary.of(baek, new BigDecimal("4.5"), 23));
        summary.updateContent("맵지 않아 아이들이 잘 먹는다는 평이 많아요");
        entityManager.flush();
        entityManager.clear();

        // when
        ProductReviewSummary found = productReviewSummaryRepository.findById(baek.getId()).orElseThrow();

        // then
        assertThat(found.getContent()).isEqualTo("맵지 않아 아이들이 잘 먹는다는 평이 많아요");
    }

    private Product persistProduct(Ingredient ingredient, String productCode, String name) {
        Product product = Product.of(productCode, ingredient, "테스트브랜드", name, null, 10_000L, 1000, "1kg");
        entityManager.persist(product);
        return product;
    }
}
