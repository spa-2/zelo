package com.server.zelo.domain.inventory.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class InventoryQuantityTest {

    @Mock
    private Product product;

    @ParameterizedTest
    @CsvSource({"1, 1, 9", "10, 10, 0"})
    void 선점하면_실재고를_유지하고_선점과_가용_수량을_변경한다(
            Integer quantity, Integer expectedReserved, Integer expectedAvailable) {
        // given
        Inventory inventory = Inventory.of(product, 10);

        // when
        inventory.reserve(quantity);

        // then
        assertQuantities(inventory, 10, expectedReserved, expectedAvailable);
    }

    @Test
    void 연속으로_선점하면_남은_가용_수량을_기준으로_누적한다() {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);

        // when
        inventory.reserve(6);

        // then
        assertQuantities(inventory, 10, 10, 0);
    }

    @Test
    void 품절_재고는_선점할_수_없다() {
        // given
        Inventory inventory = Inventory.of(product, 0);

        // when
        // then
        assertThatThrownBy(() -> inventory.reserve(1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        assertQuantities(inventory, 0, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 11, Integer.MAX_VALUE})
    void 가용_수량보다_많이_선점하면_예외가_발생하고_수량을_유지한다(Integer quantity) {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);

        // when
        // then
        assertThatThrownBy(() -> inventory.reserve(quantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
        assertQuantities(inventory, 10, 4, 6);
    }

    @ParameterizedTest
    @CsvSource({"1, 9, 3", "4, 6, 0"})
    void 차감하면_실재고와_선점을_줄이고_가용_수량을_유지한다(
            Integer quantity, Integer expectedStock, Integer expectedReserved) {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);

        // when
        inventory.deduct(quantity);

        // then
        assertQuantities(inventory, expectedStock, expectedReserved, 6);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, Integer.MAX_VALUE})
    void 선점보다_많이_차감하면_예외가_발생하고_수량을_유지한다(Integer quantity) {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);

        // when
        // then
        assertThatThrownBy(() -> inventory.deduct(quantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STOCK));
        assertQuantities(inventory, 10, 4, 6);
    }

    @ParameterizedTest
    @CsvSource({"1, 3, 7", "4, 0, 10"})
    void 해제하면_실재고를_유지하고_선점을_가용_수량으로_돌린다(
            Integer quantity, Integer expectedReserved, Integer expectedAvailable) {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);

        // when
        inventory.release(quantity);

        // then
        assertQuantities(inventory, 10, expectedReserved, expectedAvailable);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, Integer.MAX_VALUE})
    void 선점보다_많이_해제하면_예외가_발생하고_수량을_유지한다(Integer quantity) {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);

        // when
        // then
        assertThatThrownBy(() -> inventory.release(quantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STOCK));
        assertQuantities(inventory, 10, 4, 6);
    }

    @Test
    void 선점이_없으면_차감할_수_없다() {
        // given
        Inventory inventory = Inventory.of(product, 10);

        // when
        // then
        assertThatThrownBy(() -> inventory.deduct(1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STOCK));
        assertQuantities(inventory, 10, 0, 10);
    }

    @Test
    void 선점이_없으면_해제할_수_없다() {
        // given
        Inventory inventory = Inventory.of(product, 10);

        // when
        // then
        assertThatThrownBy(() -> inventory.release(1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STOCK));
        assertQuantities(inventory, 10, 0, 10);
    }

    @ParameterizedTest
    @CsvSource({"1, 7, 5", "4, 10, 8"})
    void 복원하면_실재고와_가용을_늘리고_남아_있는_선점을_유지한다(
            Integer quantity, Integer expectedStock, Integer expectedAvailable) {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(6);
        inventory.deduct(4);

        // when
        inventory.restore(quantity);

        // then
        assertQuantities(inventory, expectedStock, 2, expectedAvailable);
    }

    @Test
    void 선점이_0이어도_이미_차감한_재고를_복원할_수_있다() {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);
        inventory.deduct(4);

        // when
        inventory.restore(4);

        // then
        assertQuantities(inventory, 10, 0, 10);
    }

    @Test
    void 복원_결과가_Integer_최댓값과_같으면_허용한다() {
        // given
        Inventory inventory = Inventory.of(product, Integer.MAX_VALUE - 10);
        inventory.reserve(4);

        // when
        inventory.restore(10);

        // then
        assertQuantities(inventory, Integer.MAX_VALUE, 4, Integer.MAX_VALUE - 4);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, Integer.MAX_VALUE})
    void 복원_결과가_Integer_범위를_넘으면_예외가_발생하고_수량을_유지한다(Integer quantity) {
        // given
        Inventory inventory = Inventory.of(product, Integer.MAX_VALUE);
        inventory.reserve(4);

        // when
        // then
        assertThatThrownBy(() -> inventory.restore(quantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STOCK));
        assertQuantities(inventory, Integer.MAX_VALUE, 4, Integer.MAX_VALUE - 4);
    }

    @Test
    void 최대_수량도_선점과_차감과_복원을_거치며_음수가_되지_않는다() {
        // given
        Inventory inventory = Inventory.of(product, Integer.MAX_VALUE);

        // when
        inventory.reserve(Integer.MAX_VALUE);
        inventory.deduct(Integer.MAX_VALUE);

        // then
        assertQuantities(inventory, 0, 0, 0);

        // when
        inventory.restore(Integer.MAX_VALUE);

        // then
        assertQuantities(inventory, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
    }

    @Test
    void 선점과_부분_차감과_잔여_해제와_환불_복원_후_초기_수량이_된다() {
        // given
        Inventory inventory = Inventory.of(product, 10);

        // when
        inventory.reserve(6);
        inventory.deduct(4);
        inventory.release(2);
        inventory.restore(4);

        // then
        assertQuantities(inventory, 10, 0, 10);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void 선점_요청_수량이_없거나_1보다_작으면_수량을_변경하지_않는다(Integer quantity) {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);

        // when
        // then
        assertThatThrownBy(() -> inventory.reserve(quantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_QUANTITY));
        assertQuantities(inventory, 10, 4, 6);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void 차감_요청_수량이_없거나_1보다_작으면_수량을_변경하지_않는다(Integer quantity) {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);

        // when
        // then
        assertThatThrownBy(() -> inventory.deduct(quantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_QUANTITY));
        assertQuantities(inventory, 10, 4, 6);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void 해제_요청_수량이_없거나_1보다_작으면_수량을_변경하지_않는다(Integer quantity) {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);

        // when
        // then
        assertThatThrownBy(() -> inventory.release(quantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_QUANTITY));
        assertQuantities(inventory, 10, 4, 6);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void 복원_요청_수량이_없거나_1보다_작으면_수량을_변경하지_않는다(Integer quantity) {
        // given
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);

        // when
        // then
        assertThatThrownBy(() -> inventory.restore(quantity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_QUANTITY));
        assertQuantities(inventory, 10, 4, 6);
    }

    private void assertQuantities(Inventory inventory, int stock, int reserved, int available) {
        assertThat(inventory.getStockQuantity()).isEqualTo(stock).isNotNegative();
        assertThat(inventory.getReservedQuantity()).isEqualTo(reserved).isNotNegative();
        assertThat(inventory.getAvailableQuantity()).isEqualTo(available).isNotNegative();
        assertThat(inventory.getAvailableQuantity())
                .isEqualTo(inventory.getStockQuantity() - inventory.getReservedQuantity());
    }
}
