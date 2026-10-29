package com.server.zelo.domain.quote.model;

import com.server.zelo.domain.product.entity.Keyword;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

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
        LocalDateTime expiresAt,
        // 사용자가 말한 조건 (맵지 않게 등). 재료를 추가할 때도 같은 조건으로 고르려고 저장함
        Set<Keyword> conditions,
        // 품절이라 대신 고른 후보. 사용자가 고르기 전이라 items·totalAmount에 안 들어감
        List<SubstituteItem> substitutes,
        // 사용자가 말한 예산. 없으면 null
        Long budget,
        // 예산을 넘을 때만 채움. 상품별로 골라서 바꿈
        List<BudgetOption> budgetOptions
) {

    // 조건·후보가 없던 예전 견적 JSON을 읽어도 null이 아니게
    public Quote {
        conditions = conditions == null ? Set.of() : conditions;
        substitutes = substitutes == null ? List.of() : substitutes;
        budgetOptions = budgetOptions == null ? List.of() : budgetOptions;
    }

    // 예산을 넘을 때만 더 싼 후보를 보여줌. 후보는 예산 안으로 들어와도 지우지 않고 둠
    public boolean overBudget() {
        return budget != null && totalAmount > budget;
    }

    // 예산 없이 만드는 기존 호출용. 채팅 테스트가 후보까지 넣는 이 모양을 써서 남겨둠
    public Quote(String quoteId, Long userId, Long roomId, String menuName, List<QuoteItem> items,
                 List<ExcludedItem> excludedItems, Long totalAmount, LocalDateTime createdAt, LocalDateTime expiresAt,
                 Set<Keyword> conditions, List<SubstituteItem> substitutes) {
        this(quoteId, userId, roomId, menuName, items, excludedItems, totalAmount, createdAt, expiresAt, conditions,
                substitutes, null, List.of());
    }

    // 후보·예산 없이 만드는 기존 호출용
    public Quote(String quoteId, Long userId, Long roomId, String menuName, List<QuoteItem> items,
                 List<ExcludedItem> excludedItems, Long totalAmount, LocalDateTime createdAt, LocalDateTime expiresAt,
                 Set<Keyword> conditions) {
        this(quoteId, userId, roomId, menuName, items, excludedItems, totalAmount, createdAt, expiresAt, conditions,
                List.of());
    }

    // 조건 없이 만드는 기존 호출용. 채팅·주문·AI 테스트가 이 모양을 써서 남겨둠
    public Quote(String quoteId, Long userId, Long roomId, String menuName, List<QuoteItem> items,
                 List<ExcludedItem> excludedItems, Long totalAmount, LocalDateTime createdAt, LocalDateTime expiresAt) {
        this(quoteId, userId, roomId, menuName, items, excludedItems, totalAmount, createdAt, expiresAt, Set.of());
    }
}