package com.server.zelo.domain.quote.model;

public record QuoteItem(
        Long productId,
        String productCode,
        String productName,
        // Product.unitLabel 값 (ex: 1kg, 10구)
        String productUnit,
        Long unitPrice,
        Integer quantity,
        Long lineAmount
) {
}