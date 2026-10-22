package com.server.zelo.domain.review.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewTest {

    private final Product product = Product.of("KIMCHI-BAEK-1KG",
            Ingredient.of(Category.of("김치", true), "김치", BaseUnit.G), "백김치", null, 11_000L, 1000, "1kg");

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void 리뷰를_생성하면_작성자와_상품과_평점과_내용을_갖는다(int rating) {
        // when
        Review review = Review.of(9_000_201L, product, rating, "하나도 안 매워서 애들이 잘 먹어요");

        // then
        assertThat(review.getUserId()).isEqualTo(9_000_201L);
        assertThat(review.getProduct()).isSameAs(product);
        assertThat(review.getRating()).isEqualTo(rating);
        assertThat(review.getContent()).isEqualTo("하나도 안 매워서 애들이 잘 먹어요");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 6, -1})
    void 평점이_없거나_1부터_5_사이가_아니면_예외가_발생한다(Integer rating) {
        // when & then
        assertThatThrownBy(() -> Review.of(9_000_201L, product, rating, "맛있어요"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 작성자가_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> Review.of(null, product, 5, "맛있어요"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 상품이_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> Review.of(9_000_201L, null, 5, "맛있어요"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n"})
    void 내용이_비어_있으면_예외가_발생한다(String content) {
        // when & then
        assertThatThrownBy(() -> Review.of(9_000_201L, product, 5, content))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 내용은_1000자까지_허용한다() {
        // when
        Review review = Review.of(9_000_201L, product, 5, "가".repeat(1000));

        // then
        assertThat(review.getContent()).hasSize(1000);
    }

    @Test
    void 내용이_1000자를_넘으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> Review.of(9_000_201L, product, 5, "가".repeat(1001)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
