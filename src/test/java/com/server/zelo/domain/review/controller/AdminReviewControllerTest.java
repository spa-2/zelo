package com.server.zelo.domain.review.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.review.dto.response.CreateReviewSummariesResponse;
import com.server.zelo.domain.review.service.ReviewSummaryFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminReviewControllerTest {

    @Mock
    private ReviewSummaryFacade reviewSummaryFacade;

    @InjectMocks
    private AdminReviewController adminReviewController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(adminReviewController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void overwrite를_주지_않으면_요약이_없는_상품만_만들고_결과_수를_돌려준다() throws Exception {
        // given
        given(reviewSummaryFacade.createSummaries(false)).willReturn(new CreateReviewSummariesResponse(200, 198, 2));

        // when & then
        mockMvc.perform(post("/api/admin/reviews/summaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetCount").value(200))
                .andExpect(jsonPath("$.data.createdCount").value(198))
                .andExpect(jsonPath("$.data.failedCount").value(2));
    }

    @Test
    void overwrite가_true면_요약이_있는_상품까지_다시_만든다() throws Exception {
        // given
        given(reviewSummaryFacade.createSummaries(true)).willReturn(new CreateReviewSummariesResponse(200, 200, 0));

        // when & then
        mockMvc.perform(post("/api/admin/reviews/summaries").param("overwrite", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(200));
    }

    @Test
    void 요약_생성이_이미_돌고_있으면_409를_돌려준다() throws Exception {
        // given
        given(reviewSummaryFacade.createSummaries(false)).willThrow(new BusinessException(ErrorCode.TIMEOUT_EXCEPTION));

        // when & then
        mockMvc.perform(post("/api/admin/reviews/summaries"))
                .andExpect(status().isConflict());
    }
}
