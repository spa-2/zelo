package com.server.zelo.domain.review.service;

import com.server.zelo.ai.llm.LlmGateway;
import com.server.zelo.ai.llm.LlmReply;
import com.server.zelo.ai.llm.ReviewSummaryPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.domain.review.entity.Review;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// @Transactional을 걸지 않는다: 상품마다 LLM 응답을 기다리는 동안(재시도 포함 약 10초) DB 커넥션을 붙잡지 않도록
// 조회와 저장은 ReviewService의 짧은 트랜잭션으로 나누고, LLM 호출은 그 사이(트랜잭션 밖)에서 한다
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewCommandService {

    private final LlmGateway llmGateway;
    private final ReviewService reviewService;
    private final ReviewSummaryPrompt reviewSummaryPrompt;

    // 요약이 아직 없는 상품만 골라 만든다. 이미 요약이 있는 상품은 대상에서 빠지므로 여러 번 실행해도 LLM을 다시 부르지 않는다
    public int createSummaries() {
        List<Long> productIds = reviewService.findSummaryTargetProductIds();
        int created = 0;
        for (Long productId : productIds) {
            if (createSummary(productId)) {
                created++;
            }
        }
        log.info("리뷰 요약 생성 targets={} created={} failed={}", productIds.size(), created, productIds.size() - created);
        return created;
    }

    // 한 상품이 실패해도 요약은 비워 둔 채 다음 상품으로 넘어간다. 요약은 없어도 리뷰 근거(평점·언급 수)는 그대로 쓸 수 있다
    private boolean createSummary(Long productId) {
        List<Review> reviews = reviewService.findRecentReviews(productId);
        if (reviews.isEmpty()) {
            // 요약 집계에는 리뷰가 있다고 되어 있는데 리뷰가 없으면 시드 데이터가 어긋난 것이라 요약하지 않는다
            log.warn("리뷰 요약 건너뜀 productId={} reason=no-reviews", productId);
            return false;
        }
        String productName = reviews.get(0).getProduct().getName();
        try {
            LlmReply reply = llmGateway.summarizeReviews(
                    reviewSummaryPrompt.systemPrompt(), reviewSummaryPrompt.userText(productName, reviews));
            reviewService.updateSummaryContent(productId, reply.text());
            return true;
        } catch (BusinessException e) {
            log.warn("리뷰 요약 실패 productId={} error={}", productId, e.getErrorCode().getCode());
            return false;
        }
    }
}
