package com.server.zelo.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.server.zelo.ai.llm.LlmGateway;
import com.server.zelo.ai.llm.LlmReply;
import com.server.zelo.ai.llm.ReviewSummaryPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.review.entity.Review;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

@ExtendWith(MockitoExtension.class)
class ReviewSummaryOrchestratorTest {

    private static final Long BAEK_ID = 1L;
    private static final Long POGI_ID = 2L;

    @Mock
    private LlmGateway llmGateway;

    @Mock
    private ReviewService reviewService;

    private ReviewSummaryPrompt reviewSummaryPrompt;
    private ReviewSummaryOrchestrator reviewSummaryOrchestrator;

    @BeforeEach
    void setUp() {
        // 프롬프트는 파일을 읽어 글을 만드는 것뿐이라 실제 객체를 써서, LLM에 실제로 보낼 글을 그대로 확인한다
        reviewSummaryPrompt = new ReviewSummaryPrompt(new ClassPathResource("prompts/review-summary.md"));
        reviewSummaryOrchestrator = new ReviewSummaryOrchestrator(llmGateway, reviewService, reviewSummaryPrompt);
    }

    @Test
    void 요약_대상_상품마다_최근_리뷰로_요약을_만들어_저장한다() {
        // given
        List<Review> baekReviews = reviews("백김치", "하나도 안 매워요");
        given(reviewService.findSummaryTargetProductIds()).willReturn(List.of(BAEK_ID));
        given(reviewService.findRecentReviews(BAEK_ID)).willReturn(baekReviews);
        given(llmGateway.summarizeReviews(reviewSummaryPrompt.systemPrompt(),
                reviewSummaryPrompt.userText("백김치", baekReviews)))
                .willReturn(LlmReply.of("맵지 않다는 평이 많아요", "gpt-test", 1, 1));

        // when
        int created = reviewSummaryOrchestrator.createSummaries();

        // then
        assertThat(created).isEqualTo(1);
        then(reviewService).should().updateSummaryContent(BAEK_ID, "맵지 않다는 평이 많아요");
    }

    @Test
    void 한_상품의_요약이_실패해도_비워_두고_다음_상품은_계속_만든다() {
        // given
        List<Review> baekReviews = reviews("백김치", "하나도 안 매워요");
        List<Review> pogiReviews = reviews("포기김치", "얼큰해요");
        given(reviewService.findSummaryTargetProductIds()).willReturn(List.of(BAEK_ID, POGI_ID));
        given(reviewService.findRecentReviews(BAEK_ID)).willReturn(baekReviews);
        given(reviewService.findRecentReviews(POGI_ID)).willReturn(pogiReviews);
        given(llmGateway.summarizeReviews(reviewSummaryPrompt.systemPrompt(),
                reviewSummaryPrompt.userText("백김치", baekReviews)))
                .willThrow(new BusinessException(ErrorCode.AI_TIMEOUT));
        given(llmGateway.summarizeReviews(reviewSummaryPrompt.systemPrompt(),
                reviewSummaryPrompt.userText("포기김치", pogiReviews)))
                .willReturn(LlmReply.of("얼큰하다는 평이 많아요", "gpt-test", 1, 1));

        // when
        int created = reviewSummaryOrchestrator.createSummaries();

        // then
        assertThat(created).isEqualTo(1);
        then(reviewService).should(never()).updateSummaryContent(eq(BAEK_ID), anyString());
        then(reviewService).should().updateSummaryContent(POGI_ID, "얼큰하다는 평이 많아요");
    }

    @Test
    void 요약_대상이_없으면_LLM을_부르지_않는다() {
        // given
        given(reviewService.findSummaryTargetProductIds()).willReturn(List.of());

        // when
        int created = reviewSummaryOrchestrator.createSummaries();

        // then
        assertThat(created).isZero();
        then(llmGateway).shouldHaveNoInteractions();
    }

    @Test
    void 요약_집계는_있는데_리뷰가_없으면_LLM을_부르지_않고_건너뛴다() {
        // given
        given(reviewService.findSummaryTargetProductIds()).willReturn(List.of(BAEK_ID));
        given(reviewService.findRecentReviews(BAEK_ID)).willReturn(List.of());

        // when
        int created = reviewSummaryOrchestrator.createSummaries();

        // then
        assertThat(created).isZero();
        then(llmGateway).shouldHaveNoInteractions();
        then(reviewService).should(never()).updateSummaryContent(anyLong(), anyString());
    }

    private List<Review> reviews(String productName, String content) {
        Product product = Product.of("KIMCHI-" + productName, Ingredient.of(Category.of("김치", true), "김치", BaseUnit.G),
                "테스트브랜드", productName, null, 10_000L, 1000, "1kg");
        return List.of(Review.of(9_000_001L, product, 5, content));
    }
}
