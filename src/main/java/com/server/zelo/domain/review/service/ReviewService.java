package com.server.zelo.domain.review.service;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import com.server.zelo.domain.review.entity.ProductReviewKeyword;
import com.server.zelo.domain.review.entity.ProductReviewSummary;
import com.server.zelo.domain.review.repository.ProductReviewKeywordRepository;
import com.server.zelo.domain.review.repository.ProductReviewSummaryRepository;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class ReviewService {

    private final ProductReviewKeywordRepository productReviewKeywordRepository;
    private final ProductReviewSummaryRepository productReviewSummaryRepository;

    // 요청한 상품은 리뷰가 없어도 모두 결과에 넣어, 호출하는 쪽이 "없음"을 따로 처리하지 않게 한다
    @Transactional(readOnly = true)
    public Map<Long, ProductReviewEvidence> findReviewEvidences(List<Long> productIds, Set<Keyword> keywords) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProductReviewSummary> summaries = productReviewSummaryRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductReviewSummary::getProductId, Function.identity()));
        Map<Long, Map<Keyword, Integer>> mentions = findMentions(productIds, keywords);

        Map<Long, ProductReviewEvidence> evidences = new LinkedHashMap<>();
        for (Long productId : productIds) {
            Map<Keyword, Integer> keywordMentions = toKeywordMentions(keywords, mentions.get(productId));
            ProductReviewSummary summary = summaries.get(productId);
            ProductReviewEvidence evidence = summary == null
                    ? ProductReviewEvidence.of(productId, null, 0, keywordMentions)
                    : ProductReviewEvidence.from(summary, keywordMentions);
            evidences.putIfAbsent(productId, evidence);
        }
        return evidences;
    }

    private Map<Long, Map<Keyword, Integer>> findMentions(List<Long> productIds, Set<Keyword> keywords) {
        if (keywords.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<Keyword, Integer>> mentions = new HashMap<>();
        for (ProductReviewKeyword productReviewKeyword
                : productReviewKeywordRepository.findAllByProductIdInAndKeywordIn(productIds, keywords)) {
            mentions.computeIfAbsent(productReviewKeyword.getProduct().getId(), id -> new EnumMap<>(Keyword.class))
                    .put(productReviewKeyword.getKeyword(), productReviewKeyword.getMentionCount());
        }
        return mentions;
    }

    // 언급이 없는 키워드도 0으로 채워, 요청한 키워드는 항상 결과에 있게 한다
    private Map<Keyword, Integer> toKeywordMentions(Set<Keyword> keywords, Map<Keyword, Integer> found) {
        Map<Keyword, Integer> keywordMentions = new EnumMap<>(Keyword.class);
        for (Keyword keyword : keywords) {
            keywordMentions.put(keyword, found == null ? 0 : found.getOrDefault(keyword, 0));
        }
        return Collections.unmodifiableMap(keywordMentions);
    }
}
