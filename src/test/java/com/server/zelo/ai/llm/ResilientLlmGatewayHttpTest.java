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
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;

// 실제 OpenAI 클라이언트가 HTTP 상태별로 던지는 예외를 ResilientLlmGateway가 맞게 나누는지, 가짜 OpenAI 서버로 요청 수를 세어 확인한다
@SpringBootTest
class ResilientLlmGatewayHttpTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private static HttpServer fakeOpenAi;
    private static volatile long delayMillis;
    private static volatile int responseStatus;
    private static final AtomicInteger requestCount = new AtomicInteger();

    @Autowired
    private LlmGateway llmGateway;

    @Autowired
    private CircuitBreaker llmCircuitBreaker;

    @DynamicPropertySource
    static void openAiProperties(DynamicPropertyRegistry registry) {
        registry.add("zelo.llm.mode", () -> "real");
        registry.add("zelo.llm.timeout", () -> TIMEOUT.toSeconds() + "s");
        registry.add("zelo.llm.retry.wait-duration", () -> "10ms");
        registry.add("spring.ai.openai.api-key", () -> "sk-test");
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + fakeOpenAi.getAddress().getPort());
    }

    @BeforeAll
    static void startFakeOpenAi() throws IOException {
        fakeOpenAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeOpenAi.createContext("/", ResilientLlmGatewayHttpTest::handle);
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
        requestCount.set(0);
        llmCircuitBreaker.reset();
    }

    @Test
    void 주입받는_LlmGateway는_감싼_구현이다() {
        // then
        assertThat(llmGateway).isInstanceOf(ResilientLlmGateway.class);
    }

    @Test
    void 서버_오류_500이면_한_번_재시도한다() {
        // given
        responseStatus = 500;

        // when & then
        assertUnavailable(ErrorCode.AI_SERVER_ERROR);
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void 요청_한도_초과_429면_한_번_재시도한다() {
        // given
        responseStatus = 429;

        // when & then
        assertUnavailable(ErrorCode.AI_RATE_LIMITED);
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void 요청_오류_400과_401은_재시도하지_않는다() {
        // given
        responseStatus = 400;

        // when & then
        assertUnavailable(ErrorCode.AI_REQUEST_REJECTED);
        responseStatus = 401;
        assertUnavailable(ErrorCode.AI_REQUEST_REJECTED);
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void 응답이_늦으면_시도마다_시간_제한에서_끊고_한_번_재시도한다() {
        // given
        delayMillis = 3_000;
        long start = System.nanoTime();

        // when & then
        assertUnavailable(ErrorCode.AI_TIMEOUT);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        // 부하가 크면 두 번째 요청이 서버에 닿기 전에 시간 제한이 끝날 수 있어 요청 수 대신 걸린 시간으로 본다:
        // 시도 2번이 각각 시간 제한만큼 기다렸고, 늦은 응답(3초)을 시도마다 끝까지 기다리지는 않았다
        assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT.multipliedBy(2).minusMillis(200))
                .isLessThan(Duration.ofMillis(delayMillis + 1_000));
    }

    @Test
    void 첫_시도가_실패해도_재시도가_성공하면_답을_돌려준다() {
        // given: 첫 요청만 503으로 답한다
        responseStatus = -503;

        // when
        LlmReply reply = llmGateway.explain("system", "질문");

        // then
        assertThat(reply.text()).isEqualTo("빠른 답변");
        assertThat(requestCount.get()).isEqualTo(2);
    }

    private void assertUnavailable(ErrorCode expected) {
        assertThatThrownBy(() -> llmGateway.explain("system", "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static void handle(HttpExchange exchange) throws IOException {
        int count = requestCount.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        sleep(delayMillis);

        // 음수 상태는 "첫 요청만 그 상태로 실패하고 다음부터는 성공"을 뜻한다
        int status = responseStatus < 0 ? (count == 1 ? -responseStatus : 200) : responseStatus;
        byte[] body = (status == 200 ? completion() : "{\"error\":{\"message\":\"boom\"}}")
                .getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = exchange.getResponseBody()) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
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
