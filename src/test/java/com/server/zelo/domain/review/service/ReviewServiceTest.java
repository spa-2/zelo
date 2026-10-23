package com.server.zelo.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import com.server.zelo.domain.review.entity.ProductReviewKeyword;
import com.server.zelo.domain.review.entity.ProductReviewSummary;
import com.server.zelo.domain.review.entity.Review;
import com.server.zelo.domain.review.repository.ProductReviewKeywordRepository;
import com.server.zelo.domain.review.repository.ProductReviewSummaryRepository;
import com.server.zelo.domain.review.repository.ReviewRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    private static final Long BAEK_ID = 1L;
    private static final Long POGI_ID = 2L;
    private static final Long NO_REVIEW_ID = 3L;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ProductReviewKeywordRepository productReviewKeywordRepository;

    @Mock
    private ProductReviewSummaryRepository productReviewSummaryRepository;

    @InjectMocks
    private ReviewService reviewService;

    @Test
    void 상품마다_평균_평점과_리뷰_수와_조건_키워드_언급_수를_돌려준다() {
        // given
        Product baek = product(BAEK_ID);
        Product pogi = product(POGI_ID);
        given(productReviewSummaryRepository.findAllById(List.of(BAEK_ID, POGI_ID))).willReturn(List.of(
                summary(baek, "4.5", 23), summary(pogi, "4.7", 30)));
        given(productReviewKeywordRepository.findAllByProductIdInAndKeywordIn(List.of(BAEK_ID, POGI_ID),
                Set.of(Keyword.NOT_SPICY))).willReturn(List.of(
                ProductReviewKeyword.of(baek, Keyword.NOT_SPICY, 15),
                ProductReviewKeyword.of(pogi, Keyword.NOT_SPICY, 1)));

        // when
        Map<Long, ProductReviewEvidence> result =
                reviewService.findReviewEvidences(List.of(BAEK_ID, POGI_ID), Set.of(Keyword.NOT_SPICY));

        // then
        assertThat(result.get(BAEK_ID)).isEqualTo(ProductReviewEvidence.of(BAEK_ID, new BigDecimal("4.50"), 23,
                Map.of(Keyword.NOT_SPICY, 15), null));
        assertThat(result.get(POGI_ID)).isEqualTo(ProductReviewEvidence.of(POGI_ID, new BigDecimal("4.70"), 30,
                Map.of(Keyword.NOT_SPICY, 1), null));
    }

    @Test
    void 리뷰가_없는_상품도_평균_평점_null과_리뷰_수_0으로_결과에_넣는다() {
        // given
        given(productReviewSummaryRepository.findAllById(List.of(NO_REVIEW_ID))).willReturn(List.of());
        given(productReviewKeywordRepository.findAllByProductIdInAndKeywordIn(List.of(NO_REVIEW_ID),
                Set.of(Keyword.NOT_SPICY))).willReturn(List.of());

        // when
        Map<Long, ProductReviewEvidence> result =
                reviewService.findReviewEvidences(List.of(NO_REVIEW_ID), Set.of(Keyword.NOT_SPICY));

        // then
        assertThat(result).containsOnlyKeys(NO_REVIEW_ID);
        assertThat(result.get(NO_REVIEW_ID)).isEqualTo(ProductReviewEvidence.of(NO_REVIEW_ID, null, 0,
                Map.of(Keyword.NOT_SPICY, 0), null));
    }

    @Test
    void 언급이_없는_조건_키워드는_0으로_채운다() {
        // given
        Product baek = product(BAEK_ID);
        given(productReviewSummaryRepository.findAllById(List.of(BAEK_ID))).willReturn(List.of(summary(baek, "4.5", 23)));
        given(productReviewKeywordRepository.findAllByProductIdInAndKeywordIn(List.of(BAEK_ID),
                Set.of(Keyword.NOT_SPICY, Keyword.ORGANIC)))
                .willReturn(List.of(ProductReviewKeyword.of(baek, Keyword.NOT_SPICY, 15)));

        // when
        Map<Long, ProductReviewEvidence> result =
                reviewService.findReviewEvidences(List.of(BAEK_ID), Set.of(Keyword.NOT_SPICY, Keyword.ORGANIC));

        // then
        assertThat(result.get(BAEK_ID).keywordMentions())
                .containsExactlyInAnyOrderEntriesOf(Map.of(Keyword.NOT_SPICY, 15, Keyword.ORGANIC, 0));
    }

    @Test
    void 조건_키워드가_없으면_언급_수를_조회하지_않고_빈_Map으로_돌려준다() {
        // given
        Product baek = product(BAEK_ID);
        given(productReviewSummaryRepository.findAllById(List.of(BAEK_ID))).willReturn(List.of(summary(baek, "4.5", 23)));

        // when
        Map<Long, ProductReviewEvidence> result = reviewService.findReviewEvidences(List.of(BAEK_ID), Set.of());

        // then
        assertThat(result.get(BAEK_ID).keywordMentions()).isEmpty();
        then(productReviewKeywordRepository).shouldHaveNoInteractions();
    }

    @Test
    void 상품_목록이_비어_있으면_조회하지_않고_빈_결과를_돌려준다() {
        // when
        Map<Long, ProductReviewEvidence> result = reviewService.findReviewEvidences(List.of(), Set.of(Keyword.NOT_SPICY));

        // then
        assertThat(result).isEmpty();
        then(productReviewSummaryRepository).shouldHaveNoInteractions();
        then(productReviewKeywordRepository).shouldHaveNoInteractions();
    }

    @Test
    void 결과는_요청한_상품_순서를_따르고_같은_상품을_두_번_요청해도_한_번만_담는다() {
        // given
        given(productReviewSummaryRepository.findAllById(any())).willReturn(List.of());

        // when
        Map<Long, ProductReviewEvidence> result =
                reviewService.findReviewEvidences(List.of(POGI_ID, BAEK_ID, POGI_ID), Set.of());

        // then
        assertThat(result.keySet()).containsExactly(POGI_ID, BAEK_ID);
    }

    @Test
    void 상품_목록이_null이면_조회하지_않고_INVALID_INPUT이_발생한다() {
        // when & then
        assertInvalidInput(null, Set.of(Keyword.NOT_SPICY));
    }

    @Test
    void 상품_목록에_null이_섞여_있으면_조회하지_않고_INVALID_INPUT이_발생한다() {
        // when & then: 그대로 두면 예외 없이 null 키 결과가 섞여 나간다
        assertInvalidInput(Arrays.asList(BAEK_ID, null), Set.of(Keyword.NOT_SPICY));
    }

    @Test
    void 조건_키워드가_null이면_조회하지_않고_INVALID_INPUT이_발생한다() {
        // when & then
        assertInvalidInput(List.of(BAEK_ID), null);
    }

    @Test
    void 조건_키워드에_null이_섞여_있으면_조회하지_않고_INVALID_INPUT이_발생한다() {
        // given
        Set<Keyword> keywords = new HashSet<>(Arrays.asList(Keyword.NOT_SPICY, null));

        // when & then
        assertInvalidInput(List.of(BAEK_ID), keywords);
    }

    @Test
    void 요약이_있으면_근거에_함께_담는다() {
        // given
        Product baek = product(BAEK_ID);
        ProductReviewSummary summary = summary(baek, "4.5", 23);
        summary.updateContent("맵지 않아 아이들이 잘 먹는다는 평이 많아요");
        given(productReviewSummaryRepository.findAllById(List.of(BAEK_ID))).willReturn(List.of(summary));

        // when
        Map<Long, ProductReviewEvidence> result = reviewService.findReviewEvidences(List.of(BAEK_ID), Set.of());

        // then
        assertThat(result.get(BAEK_ID).summary()).isEqualTo("맵지 않아 아이들이 잘 먹는다는 평이 많아요");
    }

    @Test
    void 요약_대상은_요약이_없고_리뷰가_5건_이상인_상품의_ID다() {
        // given
        given(productReviewSummaryRepository.findAllByContentIsNullAndReviewCountGreaterThanEqual(5))
                .willReturn(List.of(summary(product(BAEK_ID), "4.5", 23), summary(product(POGI_ID), "4.7", 30)));

        // when
        List<Long> result = reviewService.findSummaryTargetProductIds();

        // then
        assertThat(result).containsExactly(BAEK_ID, POGI_ID);
    }

    @Test
    void 요약에_쓸_리뷰는_최근_30개까지만_가져온다() {
        // given
        List<Review> reviews = List.of(Review.of(9_000_001L, product(BAEK_ID), 5, "맛있어요"));
        given(reviewRepository.findByProductIdOrderByIdDesc(BAEK_ID, Limit.of(30))).willReturn(reviews);

        // when
        List<Review> result = reviewService.findRecentReviews(BAEK_ID);

        // then
        assertThat(result).isSameAs(reviews);
    }

    @Test
    void 요약_문장을_저장하면_상품_요약의_content가_채워진다() {
        // given
        ProductReviewSummary summary = summary(product(BAEK_ID), "4.5", 23);
        given(productReviewSummaryRepository.findById(BAEK_ID)).willReturn(Optional.of(summary));

        // when
        reviewService.updateSummaryContent(BAEK_ID, "아삭하고 시원하다는 평이 많아요");

        // then
        assertThat(summary.getContent()).isEqualTo("아삭하고 시원하다는 평이 많아요");
    }

    @Test
    void 요약_행이_없는_상품에_요약을_저장하면_예외가_발생한다() {
        // given
        given(productReviewSummaryRepository.findById(NO_REVIEW_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reviewService.updateSummaryContent(NO_REVIEW_ID, "요약"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REVIEW_SUMMARY_NOT_FOUND));
    }

    private void assertInvalidInput(List<Long> productIds, Set<Keyword> keywords) {
        assertThatThrownBy(() -> reviewService.findReviewEvidences(productIds, keywords))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        then(productReviewSummaryRepository).shouldHaveNoInteractions();
        then(productReviewKeywordRepository).shouldHaveNoInteractions();
    }

    private Product product(Long id) {
        Product product = Product.of("KIMCHI-" + id, Ingredient.of(Category.of("김치", true), "김치", BaseUnit.G),
                "테스트브랜드", "김치", null, 10_000L, 1000, "1kg");
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    // @MapsId는 저장할 때 상품 ID를 채우므로, 저장 없이 쓰는 단위 테스트에서는 직접 넣는다
    private ProductReviewSummary summary(Product product, String avgRating, int reviewCount) {
        ProductReviewSummary summary = ProductReviewSummary.of(product, new BigDecimal(avgRating), reviewCount);
        ReflectionTestUtils.setField(summary, "productId", product.getId());
        return summary;
    }
}
