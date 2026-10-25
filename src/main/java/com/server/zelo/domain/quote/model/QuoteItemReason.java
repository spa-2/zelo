package com.server.zelo.domain.quote.model;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import java.math.BigDecimal;
import java.util.Map;

// 이 상품을 고른 근거. 채팅에서 LLM이 숫자 그대로만 말하게 판단이나 점수 없이 원래 값만 담음
public record QuoteItemReason(
        Integer salesCount,
        // 리뷰가 없으면 null
        BigDecimal avgRating,
        Integer reviewCount,
        // 사용자 조건 키워드별 리뷰 언급 수. 조건이 없으면 빈 값
        Map<Keyword, Integer> keywordMentions,
        // 조건 키워드가 붙은 상품인지. 조건에 맞는 상품이 없어 전체에서 골랐으면 false
        boolean conditionMatched
) {

    public static QuoteItemReason of(Integer salesCount, ProductReviewEvidence review, boolean conditionMatched) {
        if (review == null) {
            return new QuoteItemReason(salesCount, null, 0, Map.of(), conditionMatched);
        }
        return new QuoteItemReason(salesCount, review.avgRating(), review.reviewCount(),
                review.keywordMentions(), conditionMatched);
    }
}