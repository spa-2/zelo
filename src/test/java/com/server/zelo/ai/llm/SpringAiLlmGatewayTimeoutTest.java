package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// OpenAI 대신 테스트 안에 띄운 가짜 서버로 실제 HTTP 요청을 보내, 타임아웃과 재시도 설정이 정말 적용되는지 확인한다
// (mock으로는 HTTP 클라이언트 설정을 검증할 수 없고, 실제 OpenAI는 비용·속도 문제로 CI에서 부를 수 없다)
@SpringBootTest
class SpringAiLlmGatewayTimeoutTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private static HttpServer fakeOpenAi;
    private static volatile long delayMillis;
    private static volatile int responseStatus;
    private static volatile String lastRequestBody;
    private static volatile String lastTimeoutHeader;
    private static final AtomicInteger requestCount = new AtomicInteger();

    // 재시도·서킷 브레이커로 감싸기 전의 실제 구현만 검증한다
    @Autowired
    @Qualifier(ResilientLlmGateway.DELEGATE)
    private LlmGateway llmGateway;

    @DynamicPropertySource
    static void openAiProperties(DynamicPropertyRegistry registry) {
        registry.add("zelo.llm.mode", () -> "real");
        registry.add("zelo.llm.timeout", () -> TIMEOUT.toSeconds() + "s");
        registry.add("spring.ai.openai.api-key", () -> "sk-test");
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + fakeOpenAi.getAddress().getPort());
    }

    @BeforeAll
    static void startFakeOpenAi() throws IOException {
        fakeOpenAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeOpenAi.createContext("/", SpringAiLlmGatewayTimeoutTest::handle);
        // 늦게 답하는 요청이 다음 요청을 막지 않도록 요청마다 스레드를 쓴다
        fakeOpenAi.setExecutor(Executors.newCachedThreadPool());
        fakeOpenAi.start();
    }

    @AfterAll
    static void stopFakeOpenAi() {
        fakeOpenAi.stop(0);
    }

    @BeforeEach
    void reset() {
        delayMillis = 0;
        responseStatus = 200;
        lastRequestBody = null;
        lastTimeoutHeader = null;
        requestCount.set(0);
    }

    @Test
    void 응답이_설정한_타임아웃보다_늦으면_그_시간에_요청을_끊는다() {
        // given
        delayMillis = 3_000;
        long start = System.nanoTime();

        // when & then
        assertThatThrownBy(() -> llmGateway.explain("system", List.of(), "질문")).isInstanceOf(RuntimeException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT.minusMillis(100)).isLessThan(Duration.ofMillis(2_500));
    }

    @Test
    void 응답이_제때_오면_답을_반환하고_설정한_타임아웃과_모델로_요청한다() {
        // when
        LlmReply reply = llmGateway.explain("system", List.of(), "질문");

        // then
        assertThat(reply.text()).isEqualTo("빠른 답변");
        assertThat(lastTimeoutHeader).isEqualTo(String.valueOf(TIMEOUT.toSeconds()));
        // 요청 옵션을 직접 넣어도 application.yaml의 모델·추론 강도 설정이 유지되는지 확인한다
        assertThat(lastRequestBody).contains("\"model\":\"gpt-6-luna\"", "\"reasoning_effort\":\"low\"");
    }

    @Test
    void 서버_오류가_나도_클라이언트가_자동으로_재시도하지_않는다() {
        // given
        responseStatus = 500;

        // when & then
        assertThatThrownBy(() -> llmGateway.explain("system", List.of(), "질문")).isInstanceOf(RuntimeException.class);
        assertThat(requestCount.get()).isEqualTo(1);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastTimeoutHeader = exchange.getRequestHeaders().getFirst("X-Stainless-Timeout");
        sleep(delayMillis);

        byte[] body = (responseStatus == 200 ? completion() : "{\"error\":{\"message\":\"boom\"}}")
                .getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = exchange.getResponseBody()) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            out.write(body);
        } catch (IOException e) {
            // 클라이언트가 타임아웃으로 먼저 연결을 끊은 경우
        }
    }

    private static String completion() {
        return """
                {"id":"test","object":"chat.completion","created":1,"model":"gpt-5-mini",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"빠른 답변"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
                """;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
