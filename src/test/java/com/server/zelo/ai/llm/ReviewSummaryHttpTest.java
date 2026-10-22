package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 실제 OpenAI 클라이언트로 가짜 OpenAI 서버에 리뷰 요약을 요청해, 보내는 요청 모양과 응답 정리·길이 검증·재시도를 HTTP 경로 그대로 확인한다
@SpringBootTest
class ReviewSummaryHttpTest {

    private static final String SUMMARY = "맵지 않고 아삭해서\n아이들이 잘 먹는다는 평이 많아요";
    private static final String TOO_LONG = "가".repeat(ReviewSummaryPrompt.SUMMARY_MAX_LENGTH + 1);
    private static final String REVIEW_TEXT = "상품: 백김치\n리뷰:\n- [5점] 하나도 안 매워서 애들이 잘 먹어요";

    private static HttpServer fakeOpenAi;
    // 요청 순서대로 돌려줄 응답 내용. 마지막 것은 이후 요청에도 계속 쓴다
    private static volatile List<String> contents = List.of(SUMMARY);
    private static final List<String> requestBodies = new CopyOnWriteArrayList<>();

    @Autowired
    private LlmGateway llmGateway;

    @Autowired
    private ReviewSummaryPrompt reviewSummaryPrompt;

    @Autowired
    private CircuitBreaker llmCircuitBreaker;

    @DynamicPropertySource
    static void openAiProperties(DynamicPropertyRegistry registry) {
        registry.add("zelo.llm.mode", () -> "real");
        registry.add("zelo.llm.timeout", () -> "2s");
        registry.add("zelo.llm.retry.wait-duration", () -> "10ms");
        registry.add("spring.ai.openai.api-key", () -> "sk-test");
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + fakeOpenAi.getAddress().getPort());
    }

    @BeforeAll
    static void startFakeOpenAi() throws IOException {
        fakeOpenAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeOpenAi.createContext("/", ReviewSummaryHttpTest::handle);
        fakeOpenAi.setExecutor(Executors.newCachedThreadPool());
        fakeOpenAi.start();
    }

    @AfterAll
    static void stopFakeOpenAi() {
        fakeOpenAi.stop(0);
    }

    @BeforeEach
    void reset() {
        contents = List.of(SUMMARY);
        requestBodies.clear();
        llmCircuitBreaker.reset();
    }

    @Test
    void 리뷰_요약_요청은_리뷰_전용_프롬프트를_system으로_리뷰_글을_user로_보내고_JSON_스키마는_보내지_않는다() {
        // when
        llmGateway.summarizeReviews(reviewSummaryPrompt.systemPrompt(), REVIEW_TEXT);

        // then
        assertThat(requestBodies).singleElement().satisfies(body -> {
            assertThat(body).contains("리뷰 요약 담당").contains("하나도 안 매워서 애들이 잘 먹어요");
            // 문장 하나를 받는 요청이라 메뉴 계획과 달리 구조화 출력 옵션이 없다
            assertThat(body).doesNotContain("\"response_format\"");
        });
    }

    @Test
    void 응답의_줄바꿈을_한_칸으로_줄여_한_줄_요약으로_돌려준다() {
        // when
        LlmReply reply = llmGateway.summarizeReviews(reviewSummaryPrompt.systemPrompt(), REVIEW_TEXT);

        // then
        assertThat(reply.text()).isEqualTo("맵지 않고 아삭해서 아이들이 잘 먹는다는 평이 많아요");
        assertThat(reply.model()).isEqualTo("gpt-5-mini");
        assertThat(reply.promptTokens()).isEqualTo(50);
    }

    @Test
    void 요약이_계속_너무_길면_한_번_더_묻고_AI_INVALID_RESPONSE로_실패한다() {
        // given
        contents = List.of(TOO_LONG);

        // when & then
        assertThatThrownBy(() -> llmGateway.summarizeReviews(reviewSummaryPrompt.systemPrompt(), REVIEW_TEXT))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
        assertThat(requestBodies).hasSize(2);
    }

    @Test
    void 처음_요약이_너무_길어도_다시_물어서_짧게_답하면_돌려준다() {
        // given
        contents = List.of(TOO_LONG, SUMMARY);

        // when
        LlmReply reply = llmGateway.summarizeReviews(reviewSummaryPrompt.systemPrompt(), REVIEW_TEXT);

        // then
        assertThat(reply.text()).startsWith("맵지 않고 아삭해서");
        assertThat(requestBodies).hasSize(2);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        List<String> current = contents;
        String content = current.get(Math.min(requestBodies.size(), current.size()) - 1);
        byte[] body = completion(content).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = exchange.getResponseBody()) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            out.write(body);
        }
    }

    private static String completion(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"id":"test","object":"chat.completion","created":1,"model":"gpt-5-mini",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":50,"completion_tokens":30,"total_tokens":80}}
                """.formatted(escaped);
    }
}
