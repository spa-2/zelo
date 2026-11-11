package com.server.zelo.domain.product.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryTest {

    @Test
    void 대분류를_만들면_상위_카테고리가_없다() {
        // given
        // when
        Category category = Category.of("채소", true);

        // then
        assertThat(category.getParent()).isNull();
        assertThat(category.getName()).isEqualTo("채소");
        assertThat(category.isAgentOrderable()).isTrue();
    }

    @Test
    void 소분류를_만들면_대분류를_상위로_갖는다() {
        // given
        Category vegetable = Category.of("채소", true);

        // when
        Category subCategory = Category.of(vegetable, "양파·대파·마늘·배추");

        // then
        assertThat(subCategory.getParent()).isSameAs(vegetable);
        assertThat(subCategory.getName()).isEqualTo("양파·대파·마늘·배추");
        assertThat(subCategory.isAgentOrderable()).isTrue();
    }

    @Test
    void AI_주문_불가_대분류의_소분류는_AI_주문_불가가_된다() {
        // given
        Category liquor = Category.of("주류", false);

        // when
        Category beer = Category.of(liquor, "맥주");

        // then
        assertThat(beer.isAgentOrderable()).isFalse();
    }

    @Test
    void 상위_카테고리_없이_소분류를_만들면_예외가_발생한다() {
        // given
        Category parent = null;

        // when & then
        assertThatThrownBy(() -> Category.of(parent, "맥주"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 소분류_아래에_카테고리를_만들면_예외가_발생한다() {
        // given
        Category subCategory = Category.of(Category.of("채소", true), "콩나물·버섯");

        // when & then
        assertThatThrownBy(() -> Category.of(subCategory, "느타리버섯"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}