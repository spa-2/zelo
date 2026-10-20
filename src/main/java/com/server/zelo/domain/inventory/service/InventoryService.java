package com.server.zelo.domain.inventory.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.inventory.entity.Inventory;
import com.server.zelo.domain.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {
    private final InventoryRepository inventoryRepository;

    // ** 선점 작업 **
    @Transactional
    public void reserve(Long productId, Integer requestedQuantity) {
        Inventory inventory = findInventory(productId);
        inventory.reserve(requestedQuantity);
    }

    // ** 차감 확정 **
    @Transactional
    public void deduct(Long productId, Integer requestedQuantity) {
        Inventory inventory = findInventory(productId);
        inventory.deduct(requestedQuantity);
    }

    // ** 선점 해제 **
    @Transactional
    public void release(Long productId, Integer requestedQuantity) {
        Inventory inventory = findInventory(productId);
        inventory.release(requestedQuantity);
    }

    // ** 재고 복원 **
    @Transactional
    public void restore(Long productId, Integer requestedQuantity) {
        Inventory inventory = findInventory(productId);
        inventory.restore(requestedQuantity);
    }

    private Inventory findInventory(Long productId) {
        return inventoryRepository.findInventoryByProduct_Id(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND)); // 임시로 이 에러코드 사용
    }
}
