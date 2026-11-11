package com.server.zelo.domain.product.dto.response;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.ProductStatus;
import com.server.zelo.domain.product.model.ProductDetail;
import java.util.List;

public record GetProductResponse(
        String productCode,
        String brand,
        String name,
        String description,
        Long price,
        String unitLabel,
        String categoryName,
        List<Keyword> keywords,
        Integer availableQuantity,
        ProductStatus status
) {

    public static GetProductResponse of(ProductDetail detail, Integer availableQuantity) {
        return new GetProductResponse(
                detail.productCode(),
                detail.brand(),
                detail.name(),
                detail.description(),
                detail.price(),
                detail.unitLabel(),
                detail.categoryName(),
                detail.keywords(),
                availableQuantity,
                detail.status());
    }
}