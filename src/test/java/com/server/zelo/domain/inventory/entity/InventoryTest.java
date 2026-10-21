package com.server.zelo.domain.inventory.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class InventoryTest {

    @Mock
    private Product product;

    /**
     * 품절 수량 0을 포함한 유효한 실재고로 생성하면 상품을 보존하고 선점·가용 수량을 초기화한다.
     *
     * @param stockQuantity 0 이상의 실재고 수량
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 100, Integer.MAX_VALUE})
    void 재고를_생성하면_상품과_실재고를_보존하고_선점과_가용_수량을_초기화한다(Integer stockQuantity) {
        // given
        Product targetProduct = product;

        // when
        Inventory inventory = Inventory.of(targetProduct, stockQuantity);

        // then
        assertThat(inventory.getProduct()).isSameAs(targetProduct);
        assertThat(inventory.getStockQuantity()).isEqualTo(stockQuantity);
        assertThat(inventory.getReservedQuantity()).isZero();
        assertThat(inventory.getAvailableQuantity()).isEqualTo(stockQuantity);
    }

    /**
     * 상품이 없는 재고 생성을 INVALID_INPUT 오류로 거부한다.
     */
    @Test
    void 상품이_없으면_예외가_발생한다() {
        // given
        Integer stockQuantity = 10;

        // when
        // then
        assertThatThrownBy(() -> Inventory.of(null, stockQuantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    /**
     * 실재고 수량이 없거나 음수이면 INVALID_INPUT 오류로 거부한다.
     *
     * @param stockQuantity 유효하지 않은 실재고 수량
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void 실재고가_없거나_음수이면_예외가_발생한다(Integer stockQuantity) {
        // given
        Product targetProduct = product;

        // when
        // then
        assertThatThrownBy(() -> Inventory.of(targetProduct, stockQuantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
