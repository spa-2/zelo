package com.server.zelo.domain.review.dto.response;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.review.entity.ProductReviewSummary;
import java.math.BigDecimal;
import java.util.Map;

// 견적이 상품을 고르고 LLM이 근거를 말할 때 쓰는 리뷰 집계값. 점수는 견적 쪽에서 계산한다
public record ProductReviewEvidence(
        Long productId,
        // 리뷰가 없으면 null. 0점은 "가장 나쁜 평점"으로 읽히므로 쓰지 않는다
        BigDecimal avgRating,
        Integer reviewCount,
        // 요청한 조건 키워드만 담고, 언급이 없으면 0
        Map<Keyword, Integer> keywordMentions
) {

    public static ProductReviewEvidence of(Long productId, BigDecimal avgRating, Integer reviewCount,
                                           Map<Keyword, Integer> keywordMentions) {
        return new ProductReviewEvidence(productId, avgRating, reviewCount, keywordMentions);
    }

    public static ProductReviewEvidence from(ProductReviewSummary summary, Map<Keyword, Integer> keywordMentions) {
        return new ProductReviewEvidence(summary.getProductId(), summary.getAvgRating(), summary.getReviewCount(),
                keywordMentions);
    }
}
