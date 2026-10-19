package com.server.zelo.product.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ProductTest {

    /**
     * 유효한 가격과 내용량으로 생성하면 재료의 카테고리와 초기 판매 상태를 갖는지 검증한다.
     *
     * @param price 유효한 상품 가격
     * @param contentAmount 유효한 상품 내용량
     */
    @ParameterizedTest
    @CsvSource({"0, 1", "1000, 10", "2147483647, 2147483647"})
    void 유효한_가격과_내용량으로_상품을_생성한다(Integer price, Integer contentAmount) {
        // given
        Category category = Category.of("과일", true);
        Ingredient ingredient = Ingredient.of(category, "사과", BaseUnit.EA);

        // when
        Product product = Product.of("APPLE-BOX", ingredient, "사과 한 상자", null, price, contentAmount);

        // then
        assertThat(product.getPrice()).isEqualTo(price);
        assertThat(product.getContentAmount()).isEqualTo(contentAmount);
        assertThat(product.getCategory()).isSameAs(category);
        assertThat(product.getSalesCount()).isZero();
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    /**
     * 가격이 없거나 음수이면 INVALID_PRICE 오류가 발생하는지 검증한다.
     *
     * @param price 유효하지 않은 상품 가격
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void 가격이_없거나_음수이면_예외가_발생한다(Integer price) {
        // given
        Ingredient ingredient = Ingredient.of(Category.of("과일", true), "사과", BaseUnit.EA);

        // when
        // then
        assertThatThrownBy(() -> Product.of("APPLE-BOX", ingredient, "사과 한 상자", null, price, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_PRICE));
    }

    /**
     * 내용량이 없거나 0 이하이면 INVALID_INPUT 오류가 발생하는지 검증한다.
     *
     * @param contentAmount 유효하지 않은 상품 내용량
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void 내용량이_없거나_양수가_아니면_예외가_발생한다(Integer contentAmount) {
        // given
        Ingredient ingredient = Ingredient.of(Category.of("과일", true), "사과", BaseUnit.EA);

        // when
        // then
        assertThatThrownBy(() -> Product.of("APPLE-BOX", ingredient, "사과 한 상자", null, 0, contentAmount))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
