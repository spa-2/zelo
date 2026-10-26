package com.server.zelo.domain.product.dto.response;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductStatus;
import java.util.List;
import java.util.Set;

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

    public static GetProductResponse of(Product product, Set<Keyword> keywords, Integer availableQuantity) {
        return new GetProductResponse(
                product.getProductCode(),
                product.getBrand(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getUnitLabel(),
                product.getCategory().getName(),
                keywords.stream().sorted().toList(),
                availableQuantity,
                product.getStatus());
    }
}