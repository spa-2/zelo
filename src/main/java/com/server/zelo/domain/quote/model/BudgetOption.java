package com.server.zelo.domain.quote.model;

// 예산을 넘었을 때 같은 재료 안에서 더 싼 상품으로 바꾸는 후보. 사용자가 고른 상품만 바꿈
public record BudgetOption(
        Long productId, // 지금 견적에 담긴 상품
        QuoteItem item // 대신 담을 더 싼 상품
) {
}
