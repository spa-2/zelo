package com.server.zelo.domain.quote.model;

import java.util.List;

// 어울리는 재료 추천(참치캔 등)을 담으면 어떻게 되는지 미리 계산한 것. 견적에는 저장 안 함
public record QuoteRecommendation(
        List<QuoteItem> items,
        List<SubstituteItem> substitutes,
        List<ExcludedItem> excludedItems,
        // items를 담으면 늘어나는 금액. 후보는 사용자가 고르기 전이라 안 넣음
        Long extraAmount
) {
}