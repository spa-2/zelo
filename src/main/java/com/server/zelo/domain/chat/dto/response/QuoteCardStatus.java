package com.server.zelo.domain.chat.dto.response;

import java.util.Set;

// 견적 카드가 지금 주문할 수 있는 카드인지. 화면은 ACTIVE 카드에만 [주문하기]·[수정하기] 버튼을 그린다.
// 저장된 카드에는 쓰지 않고 조회할 때마다 계산한다(Redis 견적은 TTL로 말없이 사라져서 저장된 카드를 고칠 때가 없다)
public enum QuoteCardStatus {
    ACTIVE, // 방의 현재 견적
    REPLACED, // 그 뒤에 견적이 새로 발급되거나 수정됨
    EXPIRED, // 방에 현재 견적이 없음(10분 만료)
    ORDERED; // 이 카드로 주문함

    // 주문을 먼저 본다: 주문의 quote_id는 unique라 주문이 취소돼도 같은 카드로 다시 주문할 수 없다
    public static QuoteCardStatus of(String quoteId, String currentQuoteId, Set<String> orderedQuoteIds) {
        if (orderedQuoteIds.contains(quoteId)) {
            return ORDERED;
        }
        if (currentQuoteId == null) {
            return EXPIRED;
        }
        return quoteId.equals(currentQuoteId) ? ACTIVE : REPLACED;
    }
}
