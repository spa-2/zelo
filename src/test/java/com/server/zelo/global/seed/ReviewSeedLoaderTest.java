package com.server.zelo.global.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.repository.ProductRepository;
import com.server.zelo.domain.review.entity.ProductReviewKeyword;
import com.server.zelo.domain.review.entity.ProductReviewSummary;
import com.server.zelo.domain.review.entity.Review;
import com.server.zelo.domain.review.repository.ProductReviewKeywordRepository;
import com.server.zelo.domain.review.repository.ProductReviewSummaryRepository;
import com.server.zelo.domain.review.repository.ReviewRepository;
import com.server.zelo.domain.review.service.ReviewSummaryOrchestrator;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

// 실제 seed/reviews.json을 읽어, 상품별 집계(평균 평점·리뷰 수·키워드 언급 수)가 파일 내용과 맞는지 확인한다
@ExtendWith(MockitoExtension.class)
class ReviewSeedLoaderTest {

    // reviews.json에 리뷰가 있는 상품 코드 (상품 시드 products.csv의 코드)
    private static final List<String> SEED_PRODUCT_CODES = List.of(
            "SID-0006", "SID-0008", "SID-0007", "SID-0005", "MEA-0025", "MEA-0030", "SID-0014", "SID-0012");

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ReviewSummaryOrchestrator reviewSummaryOrchestrator;

    @Mock
    private ProductReviewKeywordRepository productReviewKeywordRepository;

    @Mock
    private ProductReviewSummaryRepository productReviewSummaryRepository;

    @Test
    void 리뷰가_이미_있으면_아무것도_넣지_않고_요약도_만들지_않는다() {
        // given
        given(reviewRepository.count()).willReturn(5L);

        // when
        loader("real").load();

        // then
        then(reviewRepository).should(never()).saveAll(any());
        then(reviewSummaryOrchestrator).shouldHaveNoInteractions();
    }

    @Test
    void 시드_파일의_리뷰를_모두_저장한다() {
        // given
        givenEmptyReviewsAndSeedProducts();
        ArgumentCaptor<List<Review>> reviewsCaptor = captor();

        // when
        loader("real").load();

        // then
        then(reviewRepository).should().saveAll(reviewsCaptor.capture());
        List<Review> reviews = reviewsCaptor.getValue();
        assertThat(reviews).hasSize(89);
        // 실제 회원 ID와 섞이지 않는 번호대부터 하나씩 붙인다
        assertThat(reviews.getFirst().getUserId()).isEqualTo(9_100_000L);
        assertThat(reviews).extracting(Review::getUserId).doesNotHaveDuplicates();
    }

    @Test
    void 상품별_평균_평점과_리뷰_수를_집계한다() {
        // given
        givenEmptyReviewsAndSeedProducts();
        ArgumentCaptor<List<ProductReviewSummary>> summariesCaptor = captor();

        // when
        loader("real").load();

        // then: 백김치 23건 평균 4.52, 포기김치(브랜드 G) 30건 평균 4.70, 포기김치 500g 3건
        then(productReviewSummaryRepository).should().saveAll(summariesCaptor.capture());
        assertThat(summariesCaptor.getValue())
                .extracting(summary -> summary.getProduct().getProductCode(), ProductReviewSummary::getReviewCount,
                        ProductReviewSummary::getAvgRating)
                .contains(
                        tuple("SID-0008", 23, new BigDecimal("4.52")),
                        tuple("SID-0006", 30, new BigDecimal("4.70")),
                        tuple("SID-0005", 3, new BigDecimal("4.33")))
                .hasSize(SEED_PRODUCT_CODES.size());
        assertThat(summariesCaptor.getValue()).allSatisfy(summary -> assertThat(summary.getContent()).isNull());
    }

    @Test
    void 리뷰에_달린_키워드를_상품별로_세어_언급된_것만_저장한다() {
        // given
        givenEmptyReviewsAndSeedProducts();
        ArgumentCaptor<List<ProductReviewKeyword>> keywordsCaptor = captor();

        // when
        loader("real").load();

        // then
        then(productReviewKeywordRepository).should().saveAll(keywordsCaptor.capture());
        List<ProductReviewKeyword> keywords = keywordsCaptor.getValue();
        assertThat(keywords).filteredOn(of("SID-0008"))
                .extracting(ProductReviewKeyword::getKeyword, ProductReviewKeyword::getMentionCount)
                .containsExactlyInAnyOrder(
                        tuple(Keyword.NOT_SPICY, 15), tuple(Keyword.LOW_SALT, 6), tuple(Keyword.SPICY, 1));
        assertThat(keywords).filteredOn(of("SID-0006"))
                .extracting(ProductReviewKeyword::getKeyword, ProductReviewKeyword::getMentionCount)
                .containsExactlyInAnyOrder(tuple(Keyword.SPICY, 14), tuple(Keyword.NOT_SPICY, 1));
        // 키워드가 하나도 언급되지 않은 상품(계란 10구)은 행을 만들지 않는다
        assertThat(keywords).filteredOn(of("MEA-0025")).isEmpty();
        assertThat(keywords).allSatisfy(keyword -> assertThat(keyword.getMentionCount()).isPositive());
    }

    @Test
    void real_모드면_리뷰를_저장한_뒤_요약을_만든다() {
        // given
        givenEmptyReviewsAndSeedProducts();

        // when
        loader("real").load();

        // then
        then(reviewSummaryOrchestrator).should().createSummaries();
    }

    @Test
    void fake_모드면_고정_문구가_요약으로_남지_않게_요약을_만들지_않는다() {
        // given
        givenEmptyReviewsAndSeedProducts();

        // when
        loader("fake").load();

        // then
        then(reviewRepository).should().saveAll(any());
        then(reviewSummaryOrchestrator).shouldHaveNoInteractions();
    }

    @Test
    void 시드_파일의_상품_코드가_상품에_없으면_시작을_멈춘다() {
        // given: 백김치(SID-0008)만 빠진 상품 목록
        given(reviewRepository.count()).willReturn(0L);
        givenTransactionRunsCallback();
        given(productRepository.findAll()).willReturn(SEED_PRODUCT_CODES.stream()
                .filter(code -> !code.equals("SID-0008"))
                .map(ReviewSeedLoaderTest::product)
                .toList());

        // when & then
        assertThatThrownBy(() -> loader("real").load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SID-0008");
        then(reviewSummaryOrchestrator).shouldHaveNoInteractions();
    }

    private ReviewSeedLoader loader(String llmMode) {
        return new ReviewSeedLoader(llmMode, JsonMapper.builder().build(), reviewRepository, productRepository,
                transactionTemplate, reviewSummaryOrchestrator, productReviewKeywordRepository,
                productReviewSummaryRepository);
    }

    private void givenEmptyReviewsAndSeedProducts() {
        given(reviewRepository.count()).willReturn(0L);
        givenTransactionRunsCallback();
        given(productRepository.findAll())
                .willReturn(SEED_PRODUCT_CODES.stream().map(ReviewSeedLoaderTest::product).toList());
    }

    // 트랜잭션 없이 콜백만 바로 실행해, 저장 내용을 확인할 수 있게 한다
    private void givenTransactionRunsCallback() {
        given(transactionTemplate.execute(any()))
                .willAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    private static Product product(String productCode) {
        return Product.of(productCode, Ingredient.of(Category.of("김치", true), "김치", BaseUnit.G), "테스트브랜드",
                productCode, null, 10_000L, 1000, "1kg");
    }

    private static Predicate<ProductReviewKeyword> of(String productCode) {
        return keyword -> keyword.getProduct().getProductCode().equals(productCode);
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<List<T>> captor() {
        return ArgumentCaptor.forClass((Class<List<T>>) (Class<?>) List.class);
    }
}
