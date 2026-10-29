package com.server.zelo.domain.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.model.QuoteItemReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// AI 메시지의 payload로 저장해, 화면이 견적 카드를 그린다. [주문하기]·[수정하기] 버튼은 카드마다 같아서 화면이 고정으로 그린다. 방을 다시 열어도 그때 본 카드가 그대로 보인다.
// 상품·가격은 LLM 답이 아니라 서버 견적에서 만든다. 화면은 주문할 때 quoteId만 보낸다(상품·가격을 보내면 조작할 수 있어서).
// 사용자 ID·방 ID·상품 코드처럼 화면에 필요 없는 견적 값은 넣지 않는다
public record QuoteCardPayload(
        String type, // 카드 종류를 구분, 견적 카드를 UI로 표시
        String quoteId, // 견적 ID. 주문할 때 서버에 이 ID를 보내 견적을 확인
        String menuName, // 어떤 메뉴를 위한 견적인지
        List<String> conditions, // 요청 조건 이름 (ex: 맵지 않은). 없으면 빈 값
        List<Item> items, // 견적이 담긴 상품 목록
        List<Excluded> excludedItems, // 상품을 찾지 못하거나 재고가 부족해 견적에서 제외된 상품
        // 원래 고를 상품이 품절이라 대신 고른 후보. 사용자가 [좋아요]를 누르기 전이라 items·totalAmount에 들어가지 않는다
        List<Substitute> substitutes,
        Long budget, // 사용자가 말한 예산. 없으면 null
        boolean overBudget, // 합계가 예산을 넘었는지. 화면은 true일 때만 예산 안내와 더 싼 후보를 그린다
        // 담긴 상품마다 더 싼 후보. 견적은 예산 안으로 들어와도 후보를 남겨 두므로, 예산을 넘었을 때만 담는다
        List<BudgetOption> budgetOptions,
        Long totalAmount, // 견적 총액
        LocalDateTime expiresAt // 견적 만료 시각
) {

    public static final String TYPE = "QUOTE_CARD";

    public static QuoteCardPayload from(Quote quote) {
        List<Item> items = quote.items().stream().map(QuoteCardPayload::toItem).toList();
        List<Excluded> excludedItems = quote.excludedItems().stream()
                .map(excludedItem -> new Excluded(excludedItem.name(), excludedItem.reason()))
                .toList();
        List<Substitute> substitutes = quote.substitutes().stream()
                .map(substitute -> new Substitute(substitute.name(), substitute.soldOutProductName(), toItem(substitute.item())))
                .toList();
        List<BudgetOption> budgetOptions = !quote.overBudget() ? List.of() : quote.budgetOptions().stream()
                .map(option -> new BudgetOption(option.productId(), toItem(option.item())))
                .toList();
        return new QuoteCardPayload(TYPE, quote.quoteId(), quote.menuName(), labels(quote.conditions()),
                items, excludedItems, substitutes, quote.budget(), quote.overBudget(), budgetOptions,
                quote.totalAmount(), quote.expiresAt());
    }

    private static Item toItem(QuoteItem item) {
        return new Item(item.productId(), item.productName(), item.productUnit(),
                item.unitPrice(), item.quantity(), item.lineAmount(), Reason.from(item.reason()));
    }

    // 견적의 조건은 Set이라 순서가 없어서, 카드마다 순서가 바뀌지 않게 Keyword 선언 순서로 정렬한다
    private static List<String> labels(Collection<Keyword> keywords) {
        return keywords.stream().sorted().map(Keyword::getLabel).toList();
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
            List<String> matchedConditions, // 상품에 맞은 요청 조건
            // 조건 상품이 있었지만 재고가 없어 다른 상품을 골랐는지. 조건과 상관없는 재료(밥, 계란)는 false
            boolean conditionSoldOut,
            BigDecimal avgRating, // 리뷰가 MIN_REVIEW_COUNT건 미만이면 null
            Integer reviewCount, // avgRating과 같이 null
            List<KeywordMention> keywordMentions, // 언급이 1건 이상인 조건만
            String summary // 리뷰 한 줄 요약. 리뷰가 적거나 아직 만들지 않았으면 null
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
                    .sorted(Map.Entry.comparingByKey())
                    .map(mention -> new KeywordMention(mention.getKey().getLabel(), mention.getValue()))
                    .toList();
            // 리뷰 쪽은 DB 자릿수대로 소수 둘째 자리(4.33)까지 주지만, 별점은 첫째 자리(4.3)로 보여 준다
            BigDecimal avgRating = enoughReviews && reason.avgRating() != null
                    ? reason.avgRating().setScale(1, RoundingMode.HALF_UP)
                    : null;
            List<String> matchedConditions = labels(reason.matchedConditions());
            boolean conditionSoldOut = matchedConditions.isEmpty() && reason.conditionProductExists();
            return new Reason(matchedConditions, conditionSoldOut, avgRating,
                    avgRating == null ? null : reason.reviewCount(), keywordMentions, reason.summary());
        }

        // 카드 JSON에 "empty" 칸으로 나가지 않게 한다 (안내 문장의 근거 줄을 넣을지 정할 때만 쓴다)
        @JsonIgnore
        public boolean isEmpty() {
            return matchedConditions.isEmpty() && !conditionSoldOut && avgRating == null
                    && keywordMentions.isEmpty() && summary == null;
        }
    }

    public record KeywordMention(
            String condition,
            Integer count
    ) {
    }

    // 화면은 ingredientName으로 [좋아요]·[빼기]를 보낸다(견적 담당이 재료 이름으로 후보를 찾음)
    public record Substitute(
            String ingredientName, // 재료 이름 (ex: 대파)
            String soldOutProductName, // 품절된 원래 상품 이름
            Item item // 대신 고른 상품. 고르기 전이라 합계에 들어가지 않음
    ) {
    }

    // 화면은 productId로 [바꾸기]를 보낸다. 상품마다 따로 골라, 마음에 드는 상품은 그대로 둘 수 있다
    public record BudgetOption(
            Long productId, // 지금 견적에 담긴 상품 (items[].productId)
            Item item // 대신 담을 더 싼 상품
    ) {
    }

    // reason: CATEGORY_NOT_ALLOWED, PRODUCT_NOT_FOUND, OUT_OF_STOCK, REJECTED_BY_USER(대체 후보를 [빼기]로 거절),
    // BLOCKED_BY_USER(사용자가 막은 카테고리) (견적 담당 코드 그대로)
    public record Excluded(
            String name,
            String reason
    ) {
    }
}
