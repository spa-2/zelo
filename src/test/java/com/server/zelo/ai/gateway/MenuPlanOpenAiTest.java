package com.server.zelo.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.ai.prompt.MenuPlanPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.service.ProductService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 실제 OpenAI를 호출해 프롬프트대로 메모를 만드는지 확인한다. 요금이 나가므로 키가 있는 로컬에서만 직접 실행한다.
// CI는 저장소에 진짜 키가 있어도 돌리지 않는다(GitHub Actions는 CI=true를 넣는다)
// LLM 답은 매번 조금씩 달라서, 표기가 흔들릴 수 있는 메뉴 이름·재료 양은 확인하지 않는다
// 재료 목록은 로컬 DB의 재료 테이블에서 읽으므로 상품 시드(ZELO_SEED_ENABLED=true)가 들어가 있어야 한다
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.+")
@SpringBootTest(properties = {"zelo.llm.mode=real", "zelo.llm.timeout=30s"})
class MenuPlanOpenAiTest {

    private static final int UNIT_REPEATS = 5;
    private static final int MIN_VEGETABLE_GRAMS = 10;
    private static final int MAX_EACH_PER_SERVING = 3;
    // #449에서 EA·BUNCH → G로 바꾼 채소 (ingredients.csv)
    private static final Set<String> GRAM_VEGETABLES = Set.of(
            "당근", "브로콜리", "파프리카", "양배추", "양파", "대파", "배추", "쪽파", "오이", "애호박", "단호박");

    // 검증·재시도까지 거친 결과를 본다
    @Autowired
    private LlmGateway llmGateway;

    @Autowired
    private MenuPlanPrompt menuPlanPrompt;

    @Autowired
    private ProductService productService;

    private Map<String, BaseUnit> ingredientUnits;

    @BeforeEach
    void setUp() {
        ingredientUnits = productService.findIngredientUnits();
        assertThat(ingredientUnits).as("로컬 DB에 상품 시드가 없음").isNotEmpty();
    }

    @Test
    void 초등학생이라는_말이_있으면_조건에_맵지_않은이_들어간다() {
        // when
        MenuPlan menuPlan = planMenu(null, List.of(), "토요일에 초등학생 둘이랑 넷이 먹을 건데, 김치볶음밥 재료 3만 원 안에서 준비해 줘");

        // then
        assertThat(menuPlan.intent()).isEqualTo(MenuPlanIntent.SHOPPING_REQUEST);
        assertThat(menuPlan.servings()).isEqualTo(4);
        assertThat(menuPlan.budget()).isEqualTo(30_000L);
        assertThat(menuPlan.conditions()).contains(Keyword.NOT_SPICY);
        assertThat(menuPlan.ingredients())
                .extracting(MenuPlanIngredient::name)
                .isSubsetOf(ingredientUnits.keySet());
    }

    @Test
    void 예전_임시_목록에_없던_메뉴도_재료_테이블의_이름으로_정리한다() {
        // when
        MenuPlan menuPlan = planMenu(null, List.of(), "된장찌개 2인분 재료 준비해 줘");

        // then
        assertThat(menuPlan.intent()).isEqualTo(MenuPlanIntent.SHOPPING_REQUEST);
        assertThat(menuPlan.ingredients())
                .extracting(MenuPlanIngredient::name)
                .isSubsetOf(ingredientUnits.keySet());
    }

    @Test
    void 키워드로_표현할_수_없는_대상은_추측하지_않고_그대로_남긴다() {
        // when
        MenuPlan menuPlan = planMenu(null, List.of(), "임산부가 먹기 좋은 김치볶음밥 2인분 재료 준비해 줘");

        // then
        assertThat(menuPlan.unmatchedConditions()).isNotEmpty();
    }

    @Test
    void 장보기가_아닌_말은_일반_질문으로_분류한다() {
        // when
        MenuPlan menuPlan = planMenu(null, List.of(), "안녕하세요");

        // then
        assertThat(menuPlan.intent()).isEqualTo(MenuPlanIntent.GENERAL_QUESTION);
    }

    @Test
    void 인분만_바꾸면_새_견적이고_앞_대화의_조건을_유지한다() {
        // given
        List<ChatMessage> history = List.of(
                ChatMessage.of(1L, ChatSender.USER, "초등학생 둘이랑 셋이 먹을 김치볶음밥 재료 준비해 줘"),
                ChatMessage.of(1L, ChatSender.ASSISTANT, "맵지 않은 김치로 김치볶음밥 3인분 재료를 준비했어요."));

        // when
        MenuPlan menuPlan = planMenu("김치볶음밥", history, "4인분으로 바꿔줘");

        // then
        assertThat(menuPlan.intent()).isEqualTo(MenuPlanIntent.SHOPPING_REQUEST);
        assertThat(menuPlan.servings()).isEqualTo(4);
        assertThat(menuPlan.conditions()).contains(Keyword.NOT_SPICY);
    }

    @Test
    void 견적이_있을_때_특정_재료만_빼는_말은_견적_수정이다() {
        // when
        MenuPlan menuPlan = planMenu("김치볶음밥", List.of(), "계란 빼줘");

        // then
        assertThat(menuPlan.intent()).isEqualTo(MenuPlanIntent.QUOTE_EDIT);
    }

    @Test
    void 견적이_없으면_재료를_빼는_말도_견적_수정이_아니다() {
        // when
        MenuPlan menuPlan = planMenu(null, List.of(), "계란 빼줘");

        // then
        assertThat(menuPlan.intent()).isNotEqualTo(MenuPlanIntent.QUOTE_EDIT);
    }

    // #449에서 G로 바뀐 채소가 개수가 아니라 그램으로 나오는지, 남은 EA 재료에 그램을 적지 않는지 본다. 메뉴 3개 × 5회 = OpenAI 15회(검증에 걸리면 재시도 1회씩 더)
    // 양은 흔들려서 정확한 값이 아니라 단위를 헷갈렸을 때만 나오는 값으로 본다: 채소 G가 10 미만(개·단 수를 적음), EA가 인원 × 3 초과(그램을 적음)
    @Test
    void 그램_단위_채소는_그램으로_개수_단위_재료는_개수로_적는다() {
        // given
        List<String> userTexts = List.of("김치볶음밥 4인분 재료 준비해 줘", "카레 4인분 재료 준비해 줘", "된장찌개 2인분 재료 준비해 줘");
        Set<String> seenVegetables = new HashSet<>();

        // when
        List<String> wrongPlans = unitConfusions(userTexts, UNIT_REPEATS, seenVegetables);

        // then
        assertThat(wrongPlans).isEmpty();
    }

    // 위 메뉴에 나오지 않는 나머지 G 채소 7개를 본다. 메뉴 4개 × 5회 = OpenAI 20회(검증에 걸리면 재시도 1회씩 더)
    @Test
    void 나머지_그램_단위_채소도_그램으로_적는다() {
        // given
        List<String> userTexts = List.of(
                "배추 겉절이 4인분 재료 준비해 줘",
                "오이무침 2인분 재료 준비해 줘",
                "단호박죽 3인분 재료 준비해 줘",
                "브로콜리랑 파프리카 넣은 닭갈비 4인분 재료 준비해 줘");
        Set<String> seenVegetables = new HashSet<>();

        // when
        List<String> wrongPlans = unitConfusions(userTexts, UNIT_REPEATS, seenVegetables);

        // then: 채소가 아예 안 나오면 확인한 게 아니므로 나왔는지도 본다
        assertThat(wrongPlans).isEmpty();
        assertThat(seenVegetables).contains("배추", "쪽파", "오이", "단호박", "브로콜리", "파프리카", "양배추");
    }

    private List<String> unitConfusions(List<String> userTexts, int repeats, Set<String> seenVegetables) {
        List<String> wrongPlans = new ArrayList<>();
        for (String userText : userTexts) {
            for (int i = 0; i < repeats; i++) {
                try {
                    MenuPlan menuPlan = planMenu(null, List.of(), userText);
                    menuPlan.ingredients().stream()
                            .map(MenuPlanIngredient::name)
                            .filter(GRAM_VEGETABLES::contains)
                            .forEach(seenVegetables::add);
                    menuPlan.ingredients().stream()
                            .filter(ingredient -> isUnitConfused(ingredient, menuPlan.servings()))
                            .forEach(ingredient -> wrongPlans.add(userText + " → " + ingredient));
                } catch (BusinessException e) {
                    wrongPlans.add(userText + " → 재시도 뒤에도 검증 실패 " + e.getErrorCode());
                }
            }
        }
        return wrongPlans;
    }

    private boolean isUnitConfused(MenuPlanIngredient ingredient, int servings) {
        if (GRAM_VEGETABLES.contains(ingredient.name())) {
            return ingredient.unit() != BaseUnit.G || ingredient.amount() < MIN_VEGETABLE_GRAMS;
        }
        return ingredient.unit() == BaseUnit.EA && ingredient.amount() > servings * MAX_EACH_PER_SERVING;
    }

    private MenuPlan planMenu(String currentMenuName, List<ChatMessage> history, String userText) {
        LlmResult<MenuPlan> result = llmGateway.planMenu(
                menuPlanPrompt.systemPrompt(ingredientUnits, currentMenuName), history, userText, ingredientUnits);
        System.out.println("[메뉴 계획] " + userText + " → " + result);
        return result.content();
    }
}
