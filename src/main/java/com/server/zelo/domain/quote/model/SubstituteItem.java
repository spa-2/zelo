package com.server.zelo.domain.quote.model;

// 원래 고를 상품이 품절이라 대신 고른 후보. 사용자가 좋다고 하기 전에는 견적 items에 넣지 않음
public record SubstituteItem(
        String name, // 재료 이름
        String soldOutProductName, // 원래 고를 상품
        QuoteItem item
) {
}
