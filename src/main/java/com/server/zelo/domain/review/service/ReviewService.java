package com.server.zelo.domain.review.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import com.server.zelo.domain.review.entity.ProductReviewKeyword;
import com.server.zelo.domain.review.entity.ProductReviewSummary;
import com.server.zelo.domain.review.entity.Review;
import com.server.zelo.domain.review.repository.ProductReviewKeywordRepository;
import com.server.zelo.domain.review.repository.ProductReviewSummaryRepository;
import com.server.zelo.domain.review.repository.ReviewRepository;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class ReviewService {

    // 리뷰가 너무 적으면 한두 명의 의견이 요약 전체가 되어 버려서, 이만큼 쌓인 상품만 요약한다
    private static final int MIN_REVIEW_COUNT_FOR_SUMMARY = 5;
    // 요약에 보내는 리뷰 수 상한. 리뷰가 많아져도 프롬프트 길이와 비용이 끝없이 늘지 않게 최근 리뷰만 보낸다
    private static final int MAX_REVIEWS_FOR_SUMMARY = 30;

    private final ReviewRepository reviewRepository;
    private final ProductReviewKeywordRepository productReviewKeywordRepository;
    private final ProductReviewSummaryRepository productReviewSummaryRepository;

    // 요청한 상품은 리뷰가 없어도 모두 결과에 넣어, 호출하는 쪽이 "없음"을 따로 처리하지 않도록 설계
    @Transactional(readOnly = true)
    public Map<Long, ProductReviewEvidence> findReviewEvidences(List<Long> productIds, Set<Keyword> keywords) {
        validateEvidenceRequest(productIds, keywords);
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
                    ? ProductReviewEvidence.of(productId, null, 0, keywordMentions, null)
                    : ProductReviewEvidence.from(summary, keywordMentions);
            evidences.putIfAbsent(productId, evidence);
        }
        return evidences;
    }

    @Transactional(readOnly = true)
    public List<Long> findSummaryTargetProductIds() {
        return productReviewSummaryRepository
                .findAllByContentIsNullAndReviewCountGreaterThanEqual(MIN_REVIEW_COUNT_FOR_SUMMARY).stream()
                .map(ProductReviewSummary::getProductId)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Review> findRecentReviews(Long productId) {
        return reviewRepository.findByProductIdOrderByIdDesc(productId, Limit.of(MAX_REVIEWS_FOR_SUMMARY));
    }

    public void updateSummaryContent(Long productId, String content) {
        ProductReviewSummary summary = productReviewSummaryRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_SUMMARY_NOT_FOUND));
        summary.updateContent(content);
    }

    // 다른 도메인(견적)이 부르는 메서드라 잘못된 입력은 공통 오류로 바로 알린다.
    // null 상품 ID는 예외 없이 null 키 결과로 섞여 나가므로 반드시 막는다.
    // List.of·Set.of는 contains(null)만 불러도 NPE를 던져서, 요소를 하나씩 보며 null을 찾는다
    private void validateEvidenceRequest(List<Long> productIds, Set<Keyword> keywords) {
        if (productIds == null || keywords == null
                || productIds.stream().anyMatch(Objects::isNull)
                || keywords.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
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
