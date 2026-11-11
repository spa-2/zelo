package com.server.zelo.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 실제 OpenAI 클라이언트로 가짜 OpenAI 서버에 도구를 붙인 답변을 요청해,
// "도구 호출 응답 → 서버가 도구 실행 → 결과를 붙여 다시 요청 → 최종 답" 왕복이 호출 한 번 안에서 끝나는지 HTTP 경로 그대로 확인한다
@SpringBootTest
class ChatAnswerToolHttpTest {

    private static final String TOOL_CALL = """
            {"id":"test-1","object":"chat.completion","created":1,"model":"gpt-6-luna",
             "choices":[{"index":0,"message":{"role":"assistant","content":null,
               "tool_calls":[{"id":"call_1","type":"function",
                 "function":{"name":"getReviewOverview","arguments":"{\\"productName\\":\\"백김치\\"}"}}]},
               "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}
            """;
    private static final String QUOTE_TOOL_CALL = """
            {"id":"test-3","object":"chat.completion","created":1,"model":"gpt-6-luna",
             "choices":[{"index":0,"message":{"role":"assistant","content":null,
               "tool_calls":[{"id":"call_2","type":"function",
                 "function":{"name":"getCurrentQuoteItems","arguments":"{}"}}]},
               "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}
            """;
    private static final String FINAL_ANSWER = """
            {"id":"test-2","object":"chat.completion","created":2,"model":"gpt-6-luna",
             "choices":[{"index":0,"message":{"role":"assistant","content":"백김치는 평점 4.5점이에요."},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":150,"completion_tokens":30,"total_tokens":180}}
            """;

    private static HttpServer fakeOpenAi;
    // 요청 순서대로 돌려줄 응답. 마지막 것은 이후 요청에도 계속 쓴다
    private static volatile List<String> responses = List.of(FINAL_ANSWER);
    private static final List<String> requestBodies = new CopyOnWriteArrayList<>();

    @Autowired
    private LlmGateway llmGateway;

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
        fakeOpenAi.createContext("/", ChatAnswerToolHttpTest::handle);
        fakeOpenAi.setExecutor(Executors.newCachedThreadPool());
        fakeOpenAi.start();
    }

    @AfterAll
    static void stopFakeOpenAi() {
        fakeOpenAi.stop(0);
    }

    @BeforeEach
    void reset() {
        responses = List.of(FINAL_ANSWER);
        requestBodies.clear();
        llmCircuitBreaker.reset();
    }

    @Test
    void 도구를_붙인_답변은_도구_목록과_추론_none을_보낸다() {
        // when
        llmGateway.answerWithTools("너는 장보기 도우미야", List.of(), "안녕", new StubReviewTools(), Map.of());

        // then: gpt-6-luna는 Chat Completions에서 도구를 추론 none일 때만 쓴다
        assertThat(requestBodies).singleElement().satisfies(body -> assertThat(body)
                .contains("\"tools\"", "getReviewOverview", "\"reasoning_effort\":\"none\"", "\"model\":\"gpt-6-luna\""));
    }

    @Test
    void LLM이_도구를_부르면_서버가_실행하고_결과를_붙여_다시_물어_최종_답을_돌려준다() {
        // given
        responses = List.of(TOOL_CALL, FINAL_ANSWER);
        StubReviewTools tools = new StubReviewTools();

        // when
        LlmReply reply = llmGateway.answerWithTools("너는 장보기 도우미야", List.of(), "백김치 리뷰 어때?", tools, Map.of());

        // then: 요청 2번이 호출 한 번 안에서 끝나고, 두 번째 요청에 도구 결과가 들어간다
        assertThat(tools.askedNames).containsExactly("백김치");
        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.get(1)).contains("\"role\":\"tool\"", "평균 평점 4.5");
        assertThat(reply.text()).isEqualTo("백김치는 평점 4.5점이에요.");
    }

    @Test
    void 방_번호는_toolContext로_도구에만_넘기고_OpenAI_요청에는_보내지_않는다() {
        // given
        responses = List.of(QUOTE_TOOL_CALL, FINAL_ANSWER);
        StubReviewTools tools = new StubReviewTools();

        // when
        llmGateway.answerWithTools("너는 장보기 도우미야", List.of(), "여기 담긴 김치 리뷰는 어때?", tools,
                Map.of("roomId", 987654321L));

        // then: LLM은 방 번호를 고를 수도 볼 수도 없고, 도구만 서버가 넣은 값을 받는다
        assertThat(tools.roomIds).containsExactly(987654321L);
        assertThat(requestBodies).hasSize(2).noneSatisfy(body -> assertThat(body).contains("987654321"));
        assertThat(requestBodies.getFirst()).doesNotContain("\"toolContext\"").contains("getCurrentQuoteItems");
    }

    static class StubReviewTools {

        private final List<String> askedNames = new CopyOnWriteArrayList<>();
        private final List<Object> roomIds = new CopyOnWriteArrayList<>();

        @Tool(description = "상품 리뷰 요약")
        public String getReviewOverview(String productName) {
            askedNames.add(productName);
            return "평균 평점 4.5";
        }

        @Tool(description = "견적에 담긴 상품")
        public String getCurrentQuoteItems(ToolContext toolContext) {
            roomIds.add(toolContext.getContext().get("roomId"));
            return "1035 백김치 1kg";
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        List<String> current = responses;
        byte[] body = current.get(Math.min(requestBodies.size(), current.size()) - 1).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = exchange.getResponseBody()) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            out.write(body);
        }
    }
}
