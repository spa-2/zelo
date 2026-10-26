package com.server.zelo.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.prompt.MenuPlanPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 실제 OpenAI 클라이언트로 가짜 OpenAI 서버에 메뉴 계획을 요청해, 보내는 요청 모양과 응답 변환·검증·재시도를 HTTP 경로 그대로 확인한다
@SpringBootTest
class MenuPlanHttpTest {

    // 테스트에서 쓰는 재료만 담은 재료 목록 (실제로는 재료 테이블에서 읽는다)
    private static final Map<String, BaseUnit> INGREDIENT_UNITS = Map.of(
            "김치", BaseUnit.G, "밥", BaseUnit.G, "계란", BaseUnit.EA, "대파", BaseUnit.BUNCH);

    private static final String VALID_PLAN = """
            {"intent":"SHOPPING_REQUEST","menuName":"김치볶음밥","servings":4,"budget":30000,
             "ingredients":[{"name":"김치","amount":400,"unit":"G"},{"name":"밥","amount":800,"unit":"G"}],
             "conditions":["NOT_SPICY"],"unmatchedConditions":[]}""";
    // 장보기 요청인데 재료가 없어 검증에 실패하는 메모
    private static final String INVALID_PLAN = """
            {"intent":"SHOPPING_REQUEST","menuName":"김치볶음밥","servings":4,"budget":0,
             "ingredients":[],"conditions":[],"unmatchedConditions":[]}""";
    private static final String NOT_JSON = "죄송해요, 다시 말씀해 주세요.";

    private static HttpServer fakeOpenAi;
    // 요청 순서대로 돌려줄 응답 내용. 마지막 것은 이후 요청에도 계속 쓴다
    private static volatile List<String> contents = List.of(VALID_PLAN);
    private static final List<String> requestBodies = new CopyOnWriteArrayList<>();

    @Autowired
    private LlmGateway llmGateway;

    @Autowired
    private MenuPlanPrompt menuPlanPrompt;

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
        fakeOpenAi.createContext("/", MenuPlanHttpTest::handle);
        fakeOpenAi.setExecutor(Executors.newCachedThreadPool());
        fakeOpenAi.start();
    }

    @AfterAll
    static void stopFakeOpenAi() {
        fakeOpenAi.stop(0);
    }

    @BeforeEach
    void reset() {
        contents = List.of(VALID_PLAN);
        requestBodies.clear();
        llmCircuitBreaker.reset();
    }

    @Test
    void 메뉴_계획_요청은_JSON_스키마를_엄격_모드로_보내고_프롬프트_파일을_system으로_보낸다() {
        // when
        planMenu("초등학생 둘이랑 넷이 김치볶음밥");

        // then
        assertThat(requestBodies).singleElement().satisfies(body -> {
            assertThat(body).contains("\"response_format\"").contains("\"json_schema\"").contains("\"strict\":true");
            // 조건 키워드는 스키마의 enum으로 묶여, 목록 밖의 값은 OpenAI가 만들지 않는다
            for (Keyword keyword : Keyword.values()) {
                assertThat(body).contains("\"" + keyword + "\"");
            }
            assertThat(body).contains("재료 목록").contains("초등학생 둘이랑 넷이 김치볶음밥");
        });
    }

    @Test
    void 응답을_MenuPlan으로_바꿔_돌려준다() {
        // when
        LlmResult<MenuPlan> result = planMenu("김치볶음밥 3만 원");

        // then
        assertThat(result.content().menuName()).isEqualTo("김치볶음밥");
        assertThat(result.content().budget()).isEqualTo(30_000L);
        assertThat(result.content().conditions()).containsExactly(Keyword.NOT_SPICY);
        assertThat(result.model()).isEqualTo("gpt-5-mini");
        assertThat(result.promptTokens()).isEqualTo(50);
    }

    @Test
    void JSON이_아닌_답이_계속되면_한_번_더_묻고_AI_INVALID_RESPONSE로_실패한다() {
        // given
        contents = List.of(NOT_JSON);

        // when & then
        assertInvalidResponse();
        assertThat(requestBodies).hasSize(2);
    }

    @Test
    void 검증에_실패하는_메모가_계속되면_한_번_더_묻고_AI_INVALID_RESPONSE로_실패한다() {
        // given
        contents = List.of(INVALID_PLAN);

        // when & then
        assertInvalidResponse();
        assertThat(requestBodies).hasSize(2);
    }

    @Test
    void 처음_답이_JSON이_아니어도_다시_물어서_맞게_답하면_돌려준다() {
        // given
        contents = List.of(NOT_JSON, VALID_PLAN);

        // when
        LlmResult<MenuPlan> result = planMenu("김치볶음밥");

        // then
        assertThat(result.content().menuName()).isEqualTo("김치볶음밥");
        assertThat(requestBodies).hasSize(2);
    }

    @Test
    void 현재_견적_메뉴와_대화_기록을_이번_말_앞에_순서대로_보낸다() {
        // given: 프롬프트 파일의 예시 문장과 겹치지 않는 말을 써야 위치로 순서를 확인할 수 있다
        List<ChatMessage> history = List.of(
                ChatMessage.of(1L, ChatSender.USER, "아이 둘이랑 셋이 먹을 불고기 재료"),
                ChatMessage.of(1L, ChatSender.ASSISTANT, "불고기 3인분 재료를 준비했어요."));

        // when
        llmGateway.planMenu(menuPlanPrompt.systemPrompt(INGREDIENT_UNITS, "불고기"), history,
                "다섯 명으로 늘려 줘", INGREDIENT_UNITS);

        // then
        assertThat(requestBodies).singleElement().satisfies(body -> {
            assertThat(body).contains("현재 견적 메뉴\\n불고기").contains("\"role\":\"assistant\"");
            int firstUser = body.indexOf("아이 둘이랑 셋이 먹을 불고기 재료");
            int assistant = body.indexOf("불고기 3인분 재료를 준비했어요.");
            int currentUser = body.indexOf("다섯 명으로 늘려 줘");
            assertThat(firstUser).isPositive().isLessThan(assistant);
            assertThat(assistant).isLessThan(currentUser);
        });
    }

    private LlmResult<MenuPlan> planMenu(String userText) {
        return llmGateway.planMenu(menuPlanPrompt.systemPrompt(INGREDIENT_UNITS, null), List.of(), userText, INGREDIENT_UNITS);
    }

    private void assertInvalidResponse() {
        assertThatThrownBy(() -> planMenu("김치볶음밥"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
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
