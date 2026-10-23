package com.server.zelo.domain.chat.dto.response;

import com.server.zelo.domain.quote.model.Quote;
import java.time.LocalDateTime;
import java.util.List;

// AI 메시지의 payload로 저장해, 화면이 견적 카드와 [예][아니오] 버튼을 그린다. 방을 다시 열어도 그때 본 카드가 그대로 보인다.
// 상품·가격은 LLM 답이 아니라 서버 견적에서 만든다. 화면은 주문할 때 quoteId만 보낸다(상품·가격을 보내면 조작할 수 있어서).
// 사용자 ID·방 ID·상품 코드처럼 화면에 필요 없는 견적 값은 넣지 않는다
public record QuoteCardPayload(
        String type,
        String quoteId,
        String menuName,
        List<Item> items,
        List<Excluded> excludedItems,
        Long totalAmount,
        LocalDateTime expiresAt,
        List<Button> buttons
) {

    public static final String TYPE = "QUOTE_CARD";
    private static final List<Button> BUTTONS = List.of(new Button("YES", "예"), new Button("NO", "아니오"));

    public static QuoteCardPayload from(Quote quote) {
        List<Item> items = quote.items().stream()
                .map(item -> new Item(item.productId(), item.productName(), item.productUnit(),
                        item.unitPrice(), item.quantity(), item.lineAmount()))
                .toList();
        List<Excluded> excludedItems = quote.excludedItems().stream()
                .map(excludedItem -> new Excluded(excludedItem.name(), excludedItem.reason()))
                .toList();
        return new QuoteCardPayload(TYPE, quote.quoteId(), quote.menuName(), items, excludedItems,
                quote.totalAmount(), quote.expiresAt(), BUTTONS);
    }

    public record Item(
            Long productId,
            String productName,
            String productUnit, // 상품 1개 단위 (ex: 1kg, 10구)
            Long unitPrice,
            Integer quantity,
            Long lineAmount
    ) {
    }

    // reason: CATEGORY_NOT_ALLOWED, PRODUCT_NOT_FOUND, OUT_OF_STOCK (견적 담당 코드 그대로)
    public record Excluded(
            String name,
            String reason
    ) {
    }

    // action은 [예]/[아니오] API가 받을 값
    public record Button(
            String action,
            String label
    ) {
    }
}
