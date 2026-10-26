package com.server.zelo.domain.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;

import com.server.zelo.ai.gateway.FakeLlmGateway;
import com.server.zelo.ai.gateway.ResilientLlmGateway;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import com.server.zelo.domain.review.entity.ProductReviewSummary;
import com.server.zelo.domain.review.entity.Review;
import com.server.zelo.domain.review.service.ReviewCommandService;
import com.server.zelo.domain.review.service.ReviewService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

// 실제 스프링 빈(장애 대응 래퍼 + FakeLlmGateway)과 실제 DB로, 요약이 커밋되어 남는지와 LLM 호출이 트랜잭션 밖에서 일어나는지 확인한다.
// 커밋을 확인해야 해서 테스트 트랜잭션을 쓰지 않고, 만든 데이터는 끝날 때 직접 지운다
@SpringBootTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewSummaryIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private FakeLlmGateway fakeLlmGateway;

    @Autowired
    private CircuitBreaker llmCircuitBreaker;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ReviewCommandService reviewCommandService;

    // LLM 호출 순간에 트랜잭션이 열려 있는지 보려고 실제 동작은 그대로 두고 호출만 엿본다
    @MockitoSpyBean
    private ResilientLlmGateway resilientLlmGateway;

    private Long productId;

    @BeforeEach
    void setUp() {
        llmCircuitBreaker.reset();
        // 카테고리·재료 이름과 상품 코드는 유니크라서, 로컬 DB에 있는 데이터와 겹치지 않을 이름을 쓴다
        productId = transactionTemplate.execute(status -> {
            Category category = Category.of("리뷰테스트-9000501", true);
            Ingredient ingredient = Ingredient.of(category, "리뷰테스트-9000501", BaseUnit.G);
            Product product = Product.of("REVIEW-TEST-9000501", ingredient, "테스트브랜드", "백김치", null, 11_000L, 1000, "1kg");
            entityManager.persist(category);
            entityManager.persist(ingredient);
            entityManager.persist(product);
            for (int i = 1; i <= 5; i++) {
                entityManager.persist(Review.of(9_000_500L + i, product, 5, "하나도 안 매워서 애들이 잘 먹어요 " + i));
            }
            entityManager.persist(ProductReviewSummary.of(product, new BigDecimal("5"), 5));
            return product.getId();
        });
    }

    @AfterEach
    void tearDown() {
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.NORMAL);
        llmCircuitBreaker.reset();
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from ProductReviewSummary s where s.productId = :id")
                    .setParameter("id", productId).executeUpdate();
            entityManager.createQuery("delete from Review r where r.product.id = :id")
                    .setParameter("id", productId).executeUpdate();
            entityManager.createQuery("delete from Product p where p.id = :id")
                    .setParameter("id", productId).executeUpdate();
            entityManager.createQuery("delete from Ingredient i where i.name = '리뷰테스트-9000501'").executeUpdate();
            entityManager.createQuery("delete from Category c where c.name = '리뷰테스트-9000501'").executeUpdate();
        });
    }

    @Test
    void 요약을_만들면_커밋되어_남고_리뷰_근거_조회에_함께_나온다() {
        // when
        reviewCommandService.createSummaries();

        // then
        ProductReviewEvidence evidence = reviewService.findReviewEvidences(List.of(productId), Set.of()).get(productId);
        assertThat(evidence.summary()).isEqualTo(FakeLlmGateway.REVIEW_SUMMARY);
        assertThat(evidence.reviewCount()).isEqualTo(5);
    }

    @Test
    void LLM은_트랜잭션_밖에서_호출한다() {
        // given
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        willAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).given(resilientLlmGateway).summarizeReviews(anyString(), anyString());

        // when
        reviewCommandService.createSummaries();

        // then: 트랜잭션 안에서 부르면 LLM을 기다리는 동안 DB 커넥션을 붙잡는다
        assertThat(transactionActive).isFalse();
    }

    @Test
    void LLM이_실패하면_요약은_비어_있고_예외가_밖으로_나가지_않는다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);

        // when
        reviewCommandService.createSummaries();

        // then
        assertThat(reviewService.findReviewEvidences(List.of(productId), Set.of()).get(productId).summary()).isNull();
    }

    @Test
    void 이미_요약이_있으면_다시_실행해도_LLM을_부르지_않는다() {
        // given
        reviewCommandService.createSummaries();
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);

        // when: 다시 부르면 실패 모드라도 대상이 아니므로 요약이 그대로 남는다
        reviewCommandService.createSummaries();

        // then
        assertThat(reviewService.findReviewEvidences(List.of(productId), Set.of()).get(productId).summary())
                .isEqualTo(FakeLlmGateway.REVIEW_SUMMARY);
    }
}
