package com.server.zelo.ai.prompt;

import com.server.zelo.domain.review.entity.ProductReviewSummary;
import com.server.zelo.domain.review.entity.Review;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

// 리뷰 요약용 system 프롬프트와, 리뷰 목록을 LLM에 보낼 글로 만든다.
// 메뉴 계획 프롬프트처럼 Spring AI 템플릿 대신 자리 표시를 직접 바꾼다(리뷰 글의 중괄호가 템플릿 해석을 깨지 않게)
@Component
public class ReviewSummaryPrompt {

    public static final int SUMMARY_MAX_LENGTH = ProductReviewSummary.CONTENT_MAX_LENGTH;

    private static final String MAX_LENGTH_PLACEHOLDER = "{{MAX_LENGTH}}";

    private final String systemPrompt;

    public ReviewSummaryPrompt(@Value("classpath:prompts/review-summary.md") Resource template) {
        this.systemPrompt = readTemplate(template).replace(MAX_LENGTH_PLACEHOLDER, String.valueOf(SUMMARY_MAX_LENGTH));
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    // 리뷰 한 건을 한 줄로 만들어, 리뷰 안의 줄바꿈 때문에 리뷰끼리 경계가 흐려지지 않게 한다
    public String userText(String productName, List<Review> reviews) {
        String reviewLines = reviews.stream()
                .map(review -> "- [" + review.getRating() + "점] " + review.getContent().strip().replaceAll("\\s+", " "))
                .collect(Collectors.joining("\n"));
        return "상품: " + productName + "\n리뷰:\n" + reviewLines;
    }

    private static String readTemplate(Resource template) {
        try {
            return template.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
