package com.server.zelo.domain.inventory.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.domain.product.entity.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private Product product;

    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private InventoryService inventoryService;

    @ParameterizedTest
    @CsvSource({
            "reserve, 10, 6, 4",
            "deduct, 8, 2, 6",
            "release, 10, 2, 8",
            "restore, 12, 4, 8"
    })
    void 상품_ID로_조회한_재고에_요청한_수량_변경을_적용한다(
            String operation, Integer stock, Integer reserved, Integer available) {
        // given
        Long productId = 7L;
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);
        given(inventoryRepository.findInventoryByProduct_Id(productId)).willReturn(Optional.of(inventory));

        // when
        execute(operation, productId, 2);

        // then
        assertQuantities(inventory, stock, reserved, available);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "deduct", "release", "restore"})
    void 재고를_찾지_못하면_404_비즈니스_예외를_전달한다(String operation) {
        // given
        Long productId = 7L;
        given(inventoryRepository.findInventoryByProduct_Id(productId)).willReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> execute(operation, productId, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "deduct", "release", "restore"})
    void 유효하지_않은_수량은_엔티티의_예외를_전달하고_수량을_유지한다(String operation) {
        // given
        Long productId = 7L;
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);
        given(inventoryRepository.findInventoryByProduct_Id(productId)).willReturn(Optional.of(inventory));

        // when
        // then
        assertThatThrownBy(() -> execute(operation, productId, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_QUANTITY));
        assertQuantities(inventory, 10, 4, 6);
    }

    /** 선점 부족 예외가 발생한 뒤 선점 수량만 증가하던 문제의 재발을 방지한다. */
    @Test
    void 선점에_실패하면_재고_수량은_변경되지_않는다() {
        // given
        Long productId = 1L;
        Inventory inventory = Inventory.of(product, 10);
        given(inventoryRepository.findInventoryByProduct_Id(productId)).willReturn(Optional.of(inventory));

        // when
        assertThatThrownBy(() -> inventoryService.reserve(productId, 11))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));

        // then
        assertQuantities(inventory, 10, 0, 10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deduct", "release"})
    void 선점_초과_차감이나_해제는_예외를_전달하고_수량을_유지한다(String operation) {
        // given
        Long productId = 7L;
        Inventory inventory = Inventory.of(product, 10);
        inventory.reserve(4);
        given(inventoryRepository.findInventoryByProduct_Id(productId)).willReturn(Optional.of(inventory));

        // when
        // then
        assertThatThrownBy(() -> execute(operation, productId, 5))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STOCK));
        assertQuantities(inventory, 10, 4, 6);
    }

    @Test
    void 복원_범위를_넘으면_예외를_전달하고_수량을_유지한다() {
        // given
        Long productId = 7L;
        Inventory inventory = Inventory.of(product, Integer.MAX_VALUE);
        given(inventoryRepository.findInventoryByProduct_Id(productId)).willReturn(Optional.of(inventory));

        // when
        // then
        assertThatThrownBy(() -> inventoryService.restore(productId, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STOCK));
        assertQuantities(inventory, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
    }

    private void execute(String operation, Long productId, Integer quantity) {
        switch (operation) {
            case "reserve" -> inventoryService.reserve(productId, quantity);
            case "deduct" -> inventoryService.deduct(productId, quantity);
            case "release" -> inventoryService.release(productId, quantity);
            case "restore" -> inventoryService.restore(productId, quantity);
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private void assertQuantities(Inventory inventory, int stock, int reserved, int available) {
        assertThat(inventory.getStockQuantity()).isEqualTo(stock);
        assertThat(inventory.getReservedQuantity()).isEqualTo(reserved);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(available);
    }
}
