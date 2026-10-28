package com.server.zelo.domain.quote.model;

// 견적에서 빠진 재료와 사유 (CATEGORY_NOT_ALLOWED, PRODUCT_NOT_FOUND, OUT_OF_STOCK, REJECTED_BY_USER)
public record ExcludedItem(
        String name,
        String reason
) {
}