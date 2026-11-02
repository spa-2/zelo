package com.server.zelo.domain.review.service;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.review.dto.response.CreateReviewSummariesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReviewSummaryFacade {

    private final ReviewCommandService reviewCommandService;
    private final LockDistributeService lockDistributeService;

    // 요약 생성이 도는 동안 다시 요청하면 기다리지 않고 409(TIMEOUT_EXCEPTION)로 돌려보낸다
    public CreateReviewSummariesResponse createSummaries(Boolean overwrite) {
        return lockDistributeService.execute(
                LockKey.createReviewSummaries(),
                () -> reviewCommandService.createSummaries(overwrite)
        );
    }
}
