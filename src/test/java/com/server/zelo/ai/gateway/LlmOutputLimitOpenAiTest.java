package com.server.zelo.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.ai.prompt.ChatPrompt;
import com.server.zelo.ai.prompt.MenuPlanPrompt;
import com.server.zelo.ai.prompt.QuoteEditPrompt;
import com.server.zelo.ai.prompt.ReviewSummaryPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

// 출력 토큰 상한(max_completion_tokens)과 잘린 답(finish_reason=length) 처리를 실제 OpenAI로 확인한다. 요금이 나가므로 키가 있는 로컬에서만 직접 실행한다.
// 재료 목록은 로컬 DB의 재료 테이블에서 읽으므로 상품 시드(ZELO_SEED_ENABLED=true)가 들어가 있어야 한다
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.+")
@SpringBootTest(properties = {"zelo.llm.mode=real", "zelo.llm.timeout=30s"})
class LlmOutputLimitOpenAiTest {

    // gpt-6-luna(low)는 답을 쓰기 전 추론 토큰부터 쓰므로, 이 정도면 답을 끝까지 쓰지 못하고 잘린다
    private static final int TINY_MAX_COMPLETION_TOKENS = 40;

    private static final Quote FRIED_RICE = new Quote("openai-test", 1L, 1L, "김치볶음밥",
            List.of(new QuoteItem(514L, "P514", "포기김치 500g", "500g", 5_900L, 1, 5_900L),
                    new QuoteItem(387L, "P387", "계란 10구", "10구", 3_900L, 1, 3_900L),
                    new QuoteItem(101L, "P101", "국산 대파 1단", "1단", 2_500L, 1, 2_500L)),
            List.of(), 12_300L, LocalDateTime.now(), LocalDateTime.now().plusMinutes(10));

    // 검증·재시도까지 거친, 앱과 같은 설정(application.yaml의 상한)의 게이트웨이
    @Autowired
    private LlmGateway llmGateway;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ChatPrompt chatPrompt;

    @Autowired
    private MenuPlanPrompt menuPlanPrompt;

    @Autowired
    private QuoteEditPrompt quoteEditPrompt;

    @Autowired
    private ReviewSummaryPrompt reviewSummaryPrompt;

    @Autowired
    private ProductService productService;

    @Value("${zelo.llm.review-summary.model}")
    private String reviewSummaryModel;

    @Value("${zelo.llm.max-completion-tokens}")
    private Integer maxCompletionTokens;

    private Map<String, BaseUnit> ingredientUnits;

    @BeforeEach
    void setUp() {
        ingredientUnits = productService.findIngredientUnits();
        assertThat(ingredientUnits).as("로컬 DB에 상품 시드가 없음").isNotEmpty();
    }

    // OpenAI 1회. 상한을 아주 작게 준 게이트웨이를 따로 만들어, 잘린 JSON이 AI_009(형식 오류)가 아니라 AI_010으로 끝나는지 본다
    @Test
    void 상한이_작아_메뉴_계획이_잘리면_AI_RESPONSE_TRUNCATED로_실패한다() {
        // given
        SpringAiLlmGateway tinyLimit = tinyLimitGateway();

        // when & then
        assertThatThrownBy(() -> tinyLimit.planMenu(menuPlanPrompt.systemPrompt(ingredientUnits, null), List.of(),
                "김치볶음밥 4인분 재료 준비해 줘", ingredientUnits))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_TRUNCATED));
    }

    // OpenAI 1회. 잘린 문장이 그대로 돌아오지 않는지 본다
    @Test
    void 상한이_작아_문장_답이_잘리면_AI_RESPONSE_TRUNCATED로_실패한다() {
        // given
        SpringAiLlmGateway tinyLimit = tinyLimitGateway();

        // when & then
        assertThatThrownBy(() -> tinyLimit.explain(chatPrompt.systemPrompt(), List.of(), "김치찌개 맛있게 끓이는 순서를 알려 줘"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_TRUNCATED));
    }

    // OpenAI 6회(검증에 걸리면 재시도 1회씩 더). 설정한 상한에서는 작업마다 답이 잘리지 않고, 출력 토큰이 상한보다 충분히 작은지 본다
    @Test
    void 설정한_상한에서는_작업별_답이_잘리지_않는다() {
        // when
        for (String userText : List.of("김치볶음밥 4인분 재료 준비해 줘", "부대찌개 6인분 재료 준비해 줘",
                "초등학생 둘 포함 5명이 먹을 카레랑 계란말이 재료 3만 원 안에서 준비해 줘")) {
            LlmResult<MenuPlan> plan = llmGateway.planMenu(menuPlanPrompt.systemPrompt(ingredientUnits, null), List.of(),
                    userText, ingredientUnits);
            print("MENU_PLAN", userText, plan.completionTokens(), maxCompletionTokens);
            assertThat(plan.completionTokens()).isLessThan(maxCompletionTokens);
        }

        String editText = "김치는 빼고 계란 2개 더, 양파 하나 추가해 줘";
        LlmResult<QuoteEditPlan> edit = llmGateway.planQuoteEdit(quoteEditPrompt.systemPrompt(FRIED_RICE, ingredientUnits),
                List.of(), editText, ingredientUnits);
        print("QUOTE_EDIT_PLAN", editText, edit.completionTokens(), maxCompletionTokens);
        assertThat(edit.completionTokens()).isLessThan(maxCompletionTokens);

        String question = "김치찌개 맛있게 끓이는 순서를 처음부터 끝까지 자세히 알려 줘";
        LlmReply answer = llmGateway.explain(chatPrompt.systemPrompt(), List.of(), question);
        print("CHAT_ANSWER", question + " → " + answer.text(), answer.completionTokens(), maxCompletionTokens);
        assertThat(answer.completionTokens()).isLessThan(maxCompletionTokens);

        String reviews = """
                상품: 국산 대파 1단
                리뷰:
                - [5점] 굵고 싱싱해서 국에 넣으니 향이 좋아요.
                - [4점] 흰 부분이 길어서 볶음에 쓰기 좋아요. 배송도 빨랐어요.
                - [5점] 냉장고에 일주일 둬도 무르지 않았어요.
                - [2점] 겉잎 몇 장이 시들어 있었어요.
                - [5점] 양이 넉넉해서 파김치까지 담갔어요.""";
        LlmReply summary = llmGateway.summarizeReviews(reviewSummaryPrompt.systemPrompt(), reviews);
        print("REVIEW_SUMMARY", summary.text(), summary.completionTokens(), maxCompletionTokens);
        assertThat(summary.completionTokens()).isLessThan(maxCompletionTokens);
    }

    private SpringAiLlmGateway tinyLimitGateway() {
        return new SpringAiLlmGateway(chatClientBuilder, Duration.ofSeconds(30), TINY_MAX_COMPLETION_TOKENS,
                reviewSummaryModel, "low");
    }

    private static void print(String operation, String text, Integer completionTokens, Integer limit) {
        System.out.printf("[출력 토큰] %s %s/%s %s%n", operation, completionTokens, limit, text);
    }
}
