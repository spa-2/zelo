package com.server.zelo.domain.product.dto.response;

import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductStatus;

public record UpdateProductResponse(
        String productCode,
        String name,
        String description,
        Long price,
        ProductStatus status
) {

    public static UpdateProductResponse from(Product product) {
        return new UpdateProductResponse(product.getProductCode(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStatus());
    }
}