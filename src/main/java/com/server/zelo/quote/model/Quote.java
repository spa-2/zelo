package com.server.zelo.quote.model;

import java.time.LocalDateTime;
import java.util.List;

// Redis quote:{roomId}에 저장하는 견적. TTL 10분, 발급·수정 때마다 덮어씀ㅁ
public record Quote(
        String quoteId,
        Long userId,
        Long roomId,
        // 주문의 order_name("김치볶음밥 재료")이 견적의 메뉴명을 쓰기 때문에 함께 저장함
        String menuName,
        List<QuoteItem> items,
        List<ExcludedItem> excludedItems,
        Long totalAmount,
        LocalDateTime createdAt,
        LocalDateTime expiresAt
) {
}