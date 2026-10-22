package com.server.zelo.domain.review.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Product;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductReviewSummaryTest {

    private final Product product = Product.of("KIMCHI-BAEK-1KG",
            Ingredient.of(Category.of("김치", true), "김치", BaseUnit.G), "테스트브랜드", "백김치", null, 11_000L, 1000, "1kg");

    @Test
    void 요약을_생성하면_평균_평점과_리뷰_수를_갖고_AI_요약은_비어_있다() {
        // when
        ProductReviewSummary summary = ProductReviewSummary.of(product, new BigDecimal("4.5"), 23);

        // then
        assertThat(summary.getProduct()).isSameAs(product);
        assertThat(summary.getAvgRating()).isEqualByComparingTo("4.50");
        assertThat(summary.getReviewCount()).isEqualTo(23);
        assertThat(summary.getContent()).isNull();
    }

    @Test
    void 평균_평점은_소수_둘째_자리로_반올림한다() {
        // when
        ProductReviewSummary summary = ProductReviewSummary.of(product, new BigDecimal("4.565"), 23);

        // then
        assertThat(summary.getAvgRating()).isEqualTo(new BigDecimal("4.57"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "5", "5.00"})
    void 평균_평점은_1부터_5까지_허용한다(String avgRating) {
        // when
        ProductReviewSummary summary = ProductReviewSummary.of(product, new BigDecimal(avgRating), 1);

        // then
        assertThat(summary.getAvgRating()).isEqualByComparingTo(avgRating);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0.99", "5.01", "0"})
    void 평균_평점이_없거나_1부터_5_사이가_아니면_예외가_발생한다(BigDecimal avgRating) {
        // when & then
        assertThatThrownBy(() -> ProductReviewSummary.of(product, avgRating, 23))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void 리뷰_수가_없거나_1보다_작으면_예외가_발생한다(Integer reviewCount) {
        // when & then
        assertThatThrownBy(() -> ProductReviewSummary.of(product, new BigDecimal("4.5"), reviewCount))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 상품이_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> ProductReviewSummary.of(null, new BigDecimal("4.5"), 23))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void AI_요약을_저장하면_content가_채워진다() {
        // given
        ProductReviewSummary summary = ProductReviewSummary.of(product, new BigDecimal("4.5"), 23);

        // when
        summary.updateContent("맵지 않아 아이들이 잘 먹는다는 평이 많아요");

        // then
        assertThat(summary.getContent()).isEqualTo("맵지 않아 아이들이 잘 먹는다는 평이 많아요");
    }

    @Test
    void AI_요약은_200자까지_허용하고_이모지도_한_글자로_센다() {
        // given
        ProductReviewSummary summary = ProductReviewSummary.of(product, new BigDecimal("4.5"), 23);
        String content = "😀".repeat(200);

        // when
        summary.updateContent(content);

        // then
        assertThat(summary.getContent()).isEqualTo(content);
    }

    @Test
    void AI_요약이_200자를_넘으면_예외가_발생한다() {
        // given
        ProductReviewSummary summary = ProductReviewSummary.of(product, new BigDecimal("4.5"), 23);

        // when & then
        assertThatThrownBy(() -> summary.updateContent("가".repeat(201)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n"})
    void AI_요약이_비어_있으면_예외가_발생한다(String content) {
        // given
        ProductReviewSummary summary = ProductReviewSummary.of(product, new BigDecimal("4.5"), 23);

        // when & then
        assertThatThrownBy(() -> summary.updateContent(content))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
