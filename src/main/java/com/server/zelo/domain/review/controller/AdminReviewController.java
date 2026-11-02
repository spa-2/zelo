package com.server.zelo.domain.review.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.domain.review.dto.response.CreateReviewSummariesResponse;
import com.server.zelo.domain.review.service.ReviewSummaryFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// /api/admin/** 는 SecurityConfig에서 ADMIN만 부를 수 있다
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/reviews")
public class AdminReviewController {

    private final ReviewSummaryFacade reviewSummaryFacade;

    // 대상 상품마다 LLM을 한 번씩 부르고 끝날 때까지 기다렸다가 결과 수를 돌려준다
    @PostMapping("/summaries")
    public ResponseEntity<ApiResponse<CreateReviewSummariesResponse>> createSummaries(
            @RequestParam(defaultValue = "false") Boolean overwrite) {
        return ResponseEntity.ok(ApiResponse.ok(reviewSummaryFacade.createSummaries(overwrite)));
    }
}
