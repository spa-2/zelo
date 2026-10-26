package com.server.zelo.domain.chat.dto.response;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItemReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// AI 메시지의 payload로 저장해, 화면이 견적 카드와 [예][아니오] 버튼을 그린다. 방을 다시 열어도 그때 본 카드가 그대로 보인다.
// 상품·가격은 LLM 답이 아니라 서버 견적에서 만든다. 화면은 주문할 때 quoteId만 보낸다(상품·가격을 보내면 조작할 수 있어서).
// 사용자 ID·방 ID·상품 코드처럼 화면에 필요 없는 견적 값은 넣지 않는다
public record QuoteCardPayload(
        String type, // 카드 종류를 구분, 견적 카드를 UI로 표시
        String quoteId, // 견적 ID. 주문할 때 서버에 이 ID를 보내 견적을 확인
        String menuName, // 어떤 메뉴를 위한 견적인지
        List<String> conditions, // 요청 조건 이름 (ex: 맵지 않은). 없으면 빈 값
        List<Item> items, // 견적이 담긴 상품 목록
        List<Excluded> excludedItems, // 상품을 찾지 못하거나 재고가 부족해 견적에서 제외된 상품
        Long totalAmount, // 견적 총액
        LocalDateTime expiresAt, // 견적 만료 시각
        List<Button> buttons // [예], [아니오] 버튼 정보
) {

    public static final String TYPE = "QUOTE_CARD";
    private static final List<Button> BUTTONS = List.of(new Button("YES", "예"), new Button("NO", "아니오"));

    public static QuoteCardPayload from(Quote quote) {
        List<Item> items = quote.items().stream()
                .map(item -> new Item(item.productId(), item.productName(), item.productUnit(),
                        item.unitPrice(), item.quantity(), item.lineAmount(), Reason.from(item.reason())))
                .toList();
        List<Excluded> excludedItems = quote.excludedItems().stream()
                .map(excludedItem -> new Excluded(excludedItem.name(), excludedItem.reason()))
                .toList();
        return new QuoteCardPayload(TYPE, quote.quoteId(), quote.menuName(), conditionNames(requestedConditions(quote)),
                items, excludedItems, quote.totalAmount(), quote.expiresAt(), BUTTONS);
    }

    // 키워드 설명의 괄호 안은 사용자 말 예시라 빼고 이름만 쓴다 (예: "맵지 않은 (예: 안 맵게, …)" → "맵지 않은")
    public static String conditionName(Keyword keyword) {
        String description = keyword.getDescription();
        int exampleStart = description.indexOf(" (");
        return exampleStart < 0 ? description : description.substring(0, exampleStart);
    }

    // Quote에 요청 조건이 아직 없어서, 리뷰 언급 수의 키(요청 조건마다 0건까지 담김)를 모아 쓴다
    private static Set<Keyword> requestedConditions(Quote quote) {
        Set<Keyword> conditions = EnumSet.noneOf(Keyword.class);
        quote.items().stream()
                .filter(item -> item.reason() != null)
                .forEach(item -> conditions.addAll(item.reason().keywordMentions().keySet()));
        return conditions;
    }

    private static List<String> conditionNames(Set<Keyword> keywords) {
        return keywords.stream().map(QuoteCardPayload::conditionName).toList();
    }

    public record Item(
            Long productId,
            String productName,
            String productUnit, // 상품 1개 단위 (ex: 1kg, 10구)
            Long unitPrice,
            Integer quantity,
            Long lineAmount,
            Reason reason // 근거 없이 만든 견적이면 null
    ) {
    }

    // 상품을 고른 근거 중 보여 줄 것만 거른 값. 카드와 견적 안내 문장(QuoteExplainPrompt)이 같이 써서 둘의 근거가 어긋나지 않는다
    public record Reason(
            List<String> matchedConditions, // 상품에 맞은 요청 조건. 맞은 조건을 알 수 없으면 빈 값
            BigDecimal avgRating, // 리뷰가 MIN_REVIEW_COUNT건 미만이면 null
            Integer reviewCount, // avgRating과 같이 null
            List<KeywordMention> keywordMentions, // 언급이 1건 이상인 조건만
            String summary // 리뷰 한 줄 요약. 아직 견적 근거에 없어서 null
    ) {

        // 견적 점수가 리뷰를 반영하는 기준과 같게 둔다. 리뷰 몇 개의 평점을 별점으로 보여 주지 않으려고
        public static final int MIN_REVIEW_COUNT = 5;

        public static Reason from(QuoteItemReason reason) {
            if (reason == null) {
                return null;
            }
            boolean enoughReviews = reason.reviewCount() != null && reason.reviewCount() >= MIN_REVIEW_COUNT;
            List<KeywordMention> keywordMentions = reason.keywordMentions().entrySet().stream()
                    .filter(mention -> mention.getValue() != null && mention.getValue() > 0)
                    .map(mention -> new KeywordMention(conditionName(mention.getKey()), mention.getValue()))
                    .toList();
            // 리뷰 쪽은 DB 자릿수대로 소수 둘째 자리(4.33)까지 주지만, 별점은 첫째 자리(4.3)로 보여 준다
            BigDecimal avgRating = enoughReviews && reason.avgRating() != null
                    ? reason.avgRating().setScale(1, RoundingMode.HALF_UP)
                    : null;
            return new Reason(matchedConditions(reason), avgRating, avgRating == null ? null : reason.reviewCount(),
                    keywordMentions, null);
        }

        public boolean isEmpty() {
            return matchedConditions.isEmpty() && avgRating == null && keywordMentions.isEmpty() && summary == null;
        }

        // conditionMatched는 맞았는지만 알려 줘서, 요청 조건이 하나일 때만 어떤 조건이 맞았는지 알 수 있다.
        // 조건이 여럿이면 맞지 않은 조건까지 맞았다고 보이지 않게 비워 둔다
        private static List<String> matchedConditions(QuoteItemReason reason) {
            Set<Keyword> conditions = reason.keywordMentions().keySet();
            if (!reason.conditionMatched() || conditions.size() != 1) {
                return List.of();
            }
            return conditionNames(conditions);
        }
    }

    public record KeywordMention(
            String condition,
            Integer count
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
