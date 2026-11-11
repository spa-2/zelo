package com.server.zelo.domain.product.dto.response;

import com.server.zelo.domain.product.entity.Product;

public record GetProductSummaryResponse(
        String productCode,
        String brand,
        String name,
        Long price,
        String unitLabel,
        String categoryName,
        Integer availableQuantity
) {

    public static GetProductSummaryResponse of(Product product, Integer availableQuantity) {
        return new GetProductSummaryResponse(
                product.getProductCode(),
                product.getBrand(),
                product.getName(),
                product.getPrice(),
                product.getUnitLabel(),
                product.getCategory().getName(),
                availableQuantity);
    }
}