package com.server.zelo.domain.inventory.service;

import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.domain.product.entity.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class InventoryAvailableQuantitiesTest {

    @Mock
    private Product firstProduct;

    @Mock
    private Product secondProduct;

    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private InventoryService inventoryService;

    @Test
    void 여러_상품의_가용_수량을_상품_ID에_맞춰_한_번에_조회한다() {
        // given
        List<Long> productIds = List.of(7L, 9L);
        Inventory firstInventory = inventory(firstProduct, 7L, 10, 3);
        Inventory secondInventory = inventory(secondProduct, 9L, 20, 4);
        given(inventoryRepository.findAllByProduct_IdIn(productIds))
                .willReturn(List.of(secondInventory, firstInventory));

        // when
        Map<Long, Integer> result = inventoryService.findAvailableQuantities(productIds);

        // then
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 7, 9L, 16));
        verify(inventoryRepository).findAllByProduct_IdIn(productIds);
        verifyNoMoreInteractions(inventoryRepository);
    }

    @Test
    void 일부_상품의_재고_행이_없으면_해당_상품만_0으로_반환한다() {
        // given
        List<Long> productIds = List.of(7L, 9L, 12L);
        Inventory inventory = inventory(firstProduct, 7L, 10, 4);
        given(inventoryRepository.findAllByProduct_IdIn(productIds)).willReturn(List.of(inventory));

        // when
        Map<Long, Integer> result = inventoryService.findAvailableQuantities(productIds);

        // then
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 6, 9L, 0, 12L, 0));
        verify(inventoryRepository).findAllByProduct_IdIn(productIds);
        verifyNoMoreInteractions(inventoryRepository);
    }

    @Test
    void 모든_상품의_재고_행이_없으면_요청한_상품을_모두_0으로_반환한다() {
        // given
        List<Long> productIds = List.of(7L, 9L);
        given(inventoryRepository.findAllByProduct_IdIn(productIds)).willReturn(List.of());

        // when
        Map<Long, Integer> result = inventoryService.findAvailableQuantities(productIds);

        // then
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 0, 9L, 0));
        verify(inventoryRepository).findAllByProduct_IdIn(productIds);
        verifyNoMoreInteractions(inventoryRepository);
    }

    @Test
    void 빈_상품_목록이면_저장소를_조회하지_않고_빈_Map을_반환한다() {
        // given
        List<Long> productIds = List.of();

        // when
        Map<Long, Integer> result = inventoryService.findAvailableQuantities(productIds);

        // then
        assertThat(result).isEmpty();
        verifyNoInteractions(inventoryRepository);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "4, 4"})
    void 재고_행이_있어도_가용_수량이_없으면_0으로_반환한다(int stock, int reserved) {
        // given
        List<Long> productIds = List.of(7L);
        Inventory inventory = inventory(firstProduct, 7L, stock, reserved);
        given(inventoryRepository.findAllByProduct_IdIn(productIds)).willReturn(List.of(inventory));

        // when
        Map<Long, Integer> result = inventoryService.findAvailableQuantities(productIds);

        // then
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 0));
        verify(inventoryRepository).findAllByProduct_IdIn(productIds);
        verifyNoMoreInteractions(inventoryRepository);
    }

    @Test
    void 중복된_상품_ID는_하나의_키로_반환한다() {
        // given
        List<Long> productIds = List.of(7L, 7L, 9L, 12L, 12L);
        Inventory inventory = inventory(firstProduct, 7L, 10, 4);
        given(inventoryRepository.findAllByProduct_IdIn(productIds)).willReturn(List.of(inventory));

        // when
        Map<Long, Integer> result = inventoryService.findAvailableQuantities(productIds);

        // then
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 6, 9L, 0, 12L, 0));
        verify(inventoryRepository).findAllByProduct_IdIn(productIds);
        verifyNoMoreInteractions(inventoryRepository);
    }

    @Test
    void Set으로_전달한_상품_ID도_일괄_조회한다() {
        // given
        Set<Long> productIds = Set.of(7L, 9L);
        Inventory inventory = inventory(firstProduct, 7L, 10, 4);
        given(inventoryRepository.findAllByProduct_IdIn(productIds)).willReturn(List.of(inventory));

        // when
        Map<Long, Integer> result = inventoryService.findAvailableQuantities(productIds);

        // then
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(7L, 6, 9L, 0));
        verify(inventoryRepository).findAllByProduct_IdIn(productIds);
        verifyNoMoreInteractions(inventoryRepository);
    }

    private Inventory inventory(Product product, Long productId, int stock, int reserved) {
        given(product.getId()).willReturn(productId);
        Inventory inventory = Inventory.of(product, stock);
        if (reserved > 0) {
            inventory.reserve(reserved);
        }
        return inventory;
    }
}
