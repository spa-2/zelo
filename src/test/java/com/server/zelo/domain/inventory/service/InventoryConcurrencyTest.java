package com.server.zelo.domain.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import com.server.zelo.support.Concurrency;
import com.server.zelo.support.Concurrency.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// 주문 생성 분산 락은 사용자별 키라 서로 다른 사용자의 주문은 재고 행 잠금만으로 줄을 선다
@SpringBootTest
@ActiveProfiles("test")
@Import(Concurrency.class)
class InventoryConcurrencyTest {

    private static final int BUYER_COUNT = 30;

    @Autowired
    private Concurrency fixture;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    private Concurrency.Stock stock;

    @AfterEach
    void tearDown() {
        fixture.delete(stock);
    }

    @Test
    void 재고보다_많은_주문이_동시에_와도_재고만큼만_선점된다() throws InterruptedException {
        // given
        stock = fixture.saveStock(10);

        // when
        Result result = fixture.runConcurrently(BUYER_COUNT,
                () -> inventoryService.reserve(stock.productId(), 1));

        // then
        Inventory inventory = inventoryRepository.findById(stock.inventoryId()).orElseThrow();
        assertThat(result.successCount()).isEqualTo(10);
        assertThat(result.errorCodes())
                .hasSize(BUYER_COUNT - 10)
                .containsOnly(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(inventory.getReservedQuantity()).isEqualTo(10);
        assertThat(inventory.getAvailableQuantity()).isZero();
    }

    @Test
    void 재고가_충분하면_동시_주문이_충돌_없이_모두_선점된다() throws InterruptedException {
        // given
        stock = fixture.saveStock(BUYER_COUNT);

        // when
        Result result = fixture.runConcurrently(BUYER_COUNT,
                () -> inventoryService.reserve(stock.productId(), 1));

        // then
        Inventory inventory = inventoryRepository.findById(stock.inventoryId()).orElseThrow();
        assertThat(result.failures()).isEmpty();
        assertThat(inventory.getReservedQuantity()).isEqualTo(BUYER_COUNT);
    }
}