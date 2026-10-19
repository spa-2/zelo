package com.server.zelo.domain.quote.model;

public record QuoteItem(
        Long productId,
        String productCode,
        String productName,
        // 주문의 order_items.product_unit("1kg", "10구") 스냅샷용. 상품 쪽 출처 미정
        String productUnit,
        Long unitPrice,
        Integer quantity,
        Long lineAmount
) {
}