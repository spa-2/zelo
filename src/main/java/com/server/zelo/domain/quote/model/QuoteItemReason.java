package com.server.zelo.domain.quote.model;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

// 이 상품을 고른 근거. 채팅에서 LLM이 숫자 그대로만 말하게 판단이나 점수 없이 원래 값만 담음
public record QuoteItemReason(
        Integer salesCount,
        // 리뷰가 없으면 null
        BigDecimal avgRating,
        Integer reviewCount,
        // 사용자 조건 키워드별 리뷰 언급 수. 조건이 없으면 빈 값
        Map<Keyword, Integer> keywordMentions,
        // 리뷰 한 줄 요약. 리뷰가 적거나 아직 안 만들었으면 null
        String summary,
        // 이 상품에 붙은 키워드 중 사용자 조건과 맞은 것. 비어 있으면 조건에 안 맞는 상품
        Set<Keyword> matchedConditions,
        // 후보 중에 조건 키워드가 붙은 상품이 있었는지 (품절 포함)
        // matchedConditions가 비었을 때 true면 "조건 상품이 품절", false면 조건과 상관없는 재료(밥, 계란 등)
        boolean conditionProductExists
) {

    public static QuoteItemReason of(Integer salesCount, ProductReviewEvidence review,
                                     Set<Keyword> matchedConditions, boolean conditionProductExists) {
        if (review == null) {
            return new QuoteItemReason(salesCount, null, 0, Map.of(), null, matchedConditions, conditionProductExists);
        }
        return new QuoteItemReason(salesCount, review.avgRating(), review.reviewCount(), review.keywordMentions(),
                review.summary(), matchedConditions, conditionProductExists);
    }
}