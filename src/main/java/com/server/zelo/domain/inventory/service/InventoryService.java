package com.server.zelo.domain.inventory.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class InventoryService {
    private final InventoryRepository inventoryRepository;

    // ** 선점 작업 **
    public void reserve(Long productId, Integer requestedQuantity) {
        Inventory inventory = findInventory(productId);
        inventory.reserve(requestedQuantity);
    }

    // ** 차감 확정 **
    public void deduct(Long productId, Integer requestedQuantity) {
        Inventory inventory = findInventory(productId);
        inventory.deduct(requestedQuantity);
    }

    // ** 선점 해제 **
    public void release(Long productId, Integer requestedQuantity) {
        Inventory inventory = findInventory(productId);
        inventory.release(requestedQuantity);
    }

    // ** 재고 복원 **
    public void restore(Long productId, Integer requestedQuantity) {
        Inventory inventory = findInventory(productId);
        inventory.restore(requestedQuantity);
    }

    // 상품별 가용수량 모두 조회
    @Transactional(readOnly = true)
    public Map<Long, Integer> findAvailableQuantities(Collection<Long> productIds) {

        // 불필요한 쿼리를 막기 위해 빈 목록이면 조회 없이 빈 Map 반환
        if (productIds.isEmpty()) {
            return Map.of();
        }

        // 상품 ID별 가용 수량 Map 반환
        Map<Long, Integer> quantities = inventoryRepository.findAllByProduct_IdIn(productIds).stream()
                .collect(Collectors.toMap(
                        inventory -> inventory.getProduct().getId(),
                        Inventory::getAvailableQuantity
                ));

        // 재고 행이 없는 상품은 0으로 채워 반환
        productIds.forEach(productId -> quantities.putIfAbsent(productId, 0));

        return quantities;
    }

    private Inventory findInventory(Long productId) {
        return inventoryRepository.findInventoryByProduct_Id(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND)); // 임시로 이 에러코드 사용
    }
}
