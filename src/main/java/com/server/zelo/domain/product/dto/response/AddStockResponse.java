package com.server.zelo.domain.product.dto.response;

import com.server.zelo.domain.inventory.entity.Inventory;

public record AddStockResponse(
        String productCode,
        Integer stockQuantity,
        Integer reservedQuantity,
        Integer availableQuantity
) {

    public static AddStockResponse of(String productCode, Inventory inventory) {
        return new AddStockResponse(productCode, inventory.getStockQuantity(), inventory.getReservedQuantity(),
                inventory.getAvailableQuantity());
    }
}
