package com.server.zelo.domain.product.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record SearchProductCondition(
        @Size(max = 50)
        String keyword,
        @Positive
        Long categoryId,
        Boolean inStock,
        ProductSort sort,
        @PositiveOrZero
        Integer page,
        @Min(1)
        @Max(100)
        Integer size
) {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;

    // 안 넘기면 전체 상품, 판매량순, 0페이지 20개
    public SearchProductCondition {
        keyword = keyword == null ? "" : keyword.strip();
        inStock = inStock != null && inStock;
        sort = sort == null ? ProductSort.SALES : sort;
        page = page == null ? DEFAULT_PAGE : page;
        size = size == null ? DEFAULT_SIZE : size;
    }
}