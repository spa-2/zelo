package com.server.zelo.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.ai.plan.QuoteEditAction;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.ai.prompt.QuoteEditPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 견적 수정 ADD에서 개수로 말한 G 채소를 그램으로 바꿔 적는지, EA 재료는 개수로 적는지 실제 OpenAI로 확인한다. 요금이 나가므로 키가 있는 로컬에서만 직접 실행한다.
// 재료 목록은 로컬 DB의 재료 테이블에서 읽으므로 상품 시드(ZELO_SEED_ENABLED=true)가 들어가 있어야 한다
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.+")
@SpringBootTest(properties = {"zelo.llm.mode=real", "zelo.llm.timeout=30s"})
class QuoteEditOpenAiTest {

    private static final int REPEATS = 2;

    // 로컬 시드의 상품. 판매 단위가 "1단"·"1개"라 그램이 아니라 개수로 적기 쉬운 조건이다
    private static final Quote FRIED_RICE = quote("김치볶음밥",
            item(514L, "포기김치 500g", "500g", 5_900L, 1),
            item(639L, "즉석밥 4개", "4개", 4_900L, 1),
            item(387L, "계란 10구", "10구", 3_900L, 1),
            item(101L, "국산 대파 1단", "1단", 2_500L, 1),
            item(98L, "양파 1개", "1개", 900L, 1));

    // 양은 흔들려서 정확한 값이 아니라 단위를 헷갈렸을 때만 나오는 값으로 본다: G 채소가 50 미만(개·단 수를 적음), EA가 말한 개수와 다름
    private static final List<Case> CASES = List.of(
            new Case("양파 하나 더 넣어줘", "양파", BaseUnit.G, amount -> amount >= 50),
            new Case("대파 한 단 더", "대파", BaseUnit.G, amount -> amount >= 50),
            new Case("오이 2개 추가해 줘", "오이", BaseUnit.G, amount -> amount >= 50),
            new Case("계란 2개 더", "계란", BaseUnit.EA, amount -> amount == 2),
            new Case("두부 한 모 추가해 줘", "두부", BaseUnit.EA, amount -> amount == 1));

    // 검증·재시도까지 거친 결과를 본다
    @Autowired
    private LlmGateway llmGateway;

    @Autowired
    private QuoteEditPrompt quoteEditPrompt;

    @Autowired
    private ProductService productService;

    private Map<String, BaseUnit> ingredientUnits;

    @BeforeEach
    void setUp() {
        ingredientUnits = productService.findIngredientUnits();
        assertThat(ingredientUnits).as("로컬 DB에 상품 시드가 없음")
                .containsEntry("양파", BaseUnit.G)
                .containsEntry("계란", BaseUnit.EA);
    }

    // 문장 5개 × 2회 = OpenAI 10회(검증에 걸리면 재시도 1회씩 더)
    @Test
    void 견적_수정_추가는_그램_재료를_그램으로_개수_재료를_개수로_적는다() {
        // given
        List<String> wrongPlans = new ArrayList<>();

        // when
        for (Case testCase : CASES) {
            for (int i = 0; i < REPEATS; i++) {
                try {
                    QuoteEditPlan plan = planQuoteEdit(testCase.text());
                    boolean added = plan.edits().stream().anyMatch(edit -> isExpectedAdd(edit, testCase));
                    if (!added) {
                        wrongPlans.add(testCase.text() + " → " + plan);
                    }
                } catch (BusinessException e) {
                    wrongPlans.add(testCase.text() + " → 재시도 뒤에도 검증 실패 " + e.getErrorCode());
                }
            }
        }

        // then
        assertThat(wrongPlans).isEmpty();
    }

    private boolean isExpectedAdd(QuoteEdit edit, Case testCase) {
        return edit.action() == QuoteEditAction.ADD
                && testCase.ingredientName().equals(edit.ingredientName())
                && edit.unit() == testCase.unit()
                && edit.amount() != null
                && testCase.amountOk().test(edit.amount());
    }

    private QuoteEditPlan planQuoteEdit(String userText) {
        LlmResult<QuoteEditPlan> result = llmGateway.planQuoteEdit(
                quoteEditPrompt.systemPrompt(FRIED_RICE, ingredientUnits), List.of(), userText, ingredientUnits);
        System.out.println("[견적 수정 계획] " + userText + " → " + result);
        return result.content();
    }

    private static Quote quote(String menuName, QuoteItem... items) {
        long total = Arrays.stream(items).mapToLong(QuoteItem::lineAmount).sum();
        LocalDateTime now = LocalDateTime.now();
        return new Quote("openai-test", 1L, 1L, menuName, List.of(items), List.of(), total, now, now.plusMinutes(10));
    }

    private static QuoteItem item(Long productId, String name, String unit, Long price, int quantity) {
        return new QuoteItem(productId, "P" + productId, name, unit, price, quantity, price * quantity);
    }

    private record Case(String text, String ingredientName, BaseUnit unit, IntPredicate amountOk) {
    }
}
