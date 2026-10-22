package com.server.zelo.domain.review.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductReviewKeywordTest {

    private final Product product = Product.of("KIMCHI-BAEK-1KG",
            Ingredient.of(Category.of("김치", true), "김치", BaseUnit.G), "테스트브랜드", "백김치", null, 11_000L, 1000, "1kg");

    @ParameterizedTest
    @ValueSource(ints = {0, 15})
    void 키워드_언급_수를_생성하면_상품과_키워드와_언급_수를_갖는다(int mentionCount) {
        // when
        ProductReviewKeyword productReviewKeyword = ProductReviewKeyword.of(product, Keyword.NOT_SPICY, mentionCount);

        // then
        assertThat(productReviewKeyword.getProduct()).isSameAs(product);
        assertThat(productReviewKeyword.getKeyword()).isEqualTo(Keyword.NOT_SPICY);
        assertThat(productReviewKeyword.getMentionCount()).isEqualTo(mentionCount);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1})
    void 언급_수가_없거나_음수이면_예외가_발생한다(Integer mentionCount) {
        // when & then
        assertThatThrownBy(() -> ProductReviewKeyword.of(product, Keyword.NOT_SPICY, mentionCount))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 상품이_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> ProductReviewKeyword.of(null, Keyword.NOT_SPICY, 15))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 키워드가_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> ProductReviewKeyword.of(product, null, 15))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
