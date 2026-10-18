package com.server.zelo.product.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
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
