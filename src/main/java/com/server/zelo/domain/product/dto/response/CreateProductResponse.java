package com.server.zelo.domain.product.dto.response;

import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductStatus;

public record CreateProductResponse(
        String productCode,
        String brand,
        String name,
        String description,
        Long price,
        Integer contentAmount,
        String unitLabel,
        String categoryName,
        ProductStatus status
) {

    public static CreateProductResponse from(Product product) {
        return new CreateProductResponse(product.getProductCode(), product.getBrand(), product.getName(),
                product.getDescription(), product.getPrice(), product.getContentAmount(), product.getUnitLabel(),
                product.getCategory().getName(), product.getStatus());
    }
}