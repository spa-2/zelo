package com.server.zelo.domain.review.dto.response;

// 실패한 상품은 요약이 바뀌지 않으므로(없으면 null, 있으면 이전 요약 유지) 같은 요청을 다시 보내면 된다
public record CreateReviewSummariesResponse(
        Integer targetCount,
        Integer createdCount,
        Integer failedCount
) {

    public static CreateReviewSummariesResponse of(Integer targetCount, Integer createdCount) {
        return new CreateReviewSummariesResponse(targetCount, createdCount, targetCount - createdCount);
    }
}
