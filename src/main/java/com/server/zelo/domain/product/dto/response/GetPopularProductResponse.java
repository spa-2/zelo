package com.server.zelo.domain.product.dto.response;

import com.server.zelo.domain.product.entity.Product;

public record GetPopularProductResponse(
        Integer rank,
        Long recentSalesCount,
        GetProductSummaryResponse product
) {

    public static GetPopularProductResponse of(Integer rank, Long recentSalesCount, Product product,
                                               Integer availableQuantity) {
        return new GetPopularProductResponse(rank, recentSalesCount,
                GetProductSummaryResponse.of(product, availableQuantity));
    }
}