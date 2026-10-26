package com.server.zelo.domain.quote.model;

public record QuoteItem(
        Long productId,
        String productCode,
        String productName,
        // Product.unitLabel 값 (ex: 1kg, 10구)
        String productUnit,
        Long unitPrice,
        Integer quantity,
        Long lineAmount,
        QuoteItemReason reason
) {

    // 근거 없이 만드는 기존 호출용. 채팅·주문·AI 테스트가 이 모양을 써서 남겨둠
    public QuoteItem(Long productId, String productCode, String productName, String productUnit,
                     Long unitPrice, Integer quantity, Long lineAmount) {
        this(productId, productCode, productName, productUnit, unitPrice, quantity, lineAmount, null);
    }
}