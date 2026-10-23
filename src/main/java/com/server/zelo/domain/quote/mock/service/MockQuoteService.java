package com.server.zelo.domain.quote.mock.service;

import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

// TODO(quote): 견적 담당의 QuoteService.validateForOrder로 교체하고 이 클래스는 삭제할 것
@Slf4j
@Service
public class MockQuoteService {

    // TODO(quote): 견적이 없거나 만료·이전 견적이면 QUOTE_EXPIRED, 타인 견적이면 NO_AUTHORITY
    public Quote validateForOrder(Long roomId, Long userId, String quoteId) {
        log.info("[MOCK] 주문용 견적 검증 roomId={}, userId={}, quoteId={}", roomId, userId, quoteId);

        LocalDateTime now = LocalDateTime.now();
        List<QuoteItem> items = List.of(
                new QuoteItem(1L, "P-001", "신선 달걀", "10구", 4500L, 1, 4500L),
                new QuoteItem(2L, "P-002", "볶음김치", "500g", 6000L, 1, 6000L)
        );
        long totalAmount = items.stream().mapToLong(QuoteItem::lineAmount).sum();

        return new Quote(quoteId, userId, roomId, "김치볶음밥 재료",
                items, List.of(), totalAmount, now, now.plusMinutes(10));
    }
}
