package com.server.zelo.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.ai.prompt.MenuPlanPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.service.ProductService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StringUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// 메뉴 계획이 검증에 걸린 뒤, 고칠 점을 붙인 재시도에서 실제 OpenAI가 "알려 준 문제"를 고쳐 오는지만 본다.
// 첫 시도는 OpenAI에 보내지 않고 틀린 답을 돌려준다. 그래서 OpenAI에는 재시도 요청만 간다
// 재시도는 처음부터 새로 답해서, 알려 준 문제와 상관없는 새 실수(예: 다른 재료 단위)가 나올 수 있다.
// 그건 고칠 점 기능의 실패가 아니라서 판정에 넣지 않고 따로 세어 출력한다 (2026-11-06 실측: 유형별 30번 중 2번)
// 요금이 나가므로 키가 있는 로컬에서만 직접 실행한다. 상품 시드가 있는 로컬 DB가 필요하다
//   계란 테스트 1번에 OpenAI 호출 10회, 유형별 테스트는 유형 10개 × 3회 = 30회
// 대파·양파 등 채소 11개는 #449부터 G라서, 개수 칸에 그램을 적는 실수는 남은 EA 재료(계란)로 본다
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.+")
@SpringBootTest(properties = {"zelo.llm.mode=real", "zelo.llm.timeout=30s"})
class MenuPlanRetryOpenAiTest {

    private static final int EACH_REPEATS = 10;
    private static final int CASE_REPEATS = 3;
    private static final String USER_TEXT = "김치볶음밥 재료 준비해 줘";
    // 틀린 답을 만들 때 바탕으로 쓰는 맞는 재료. 유형마다 한 곳만 틀리게 바꾼다
    private static final List<MenuPlanIngredient> VALID_INGREDIENTS = List.of(
            MenuPlanIngredient.of("밥", 300, BaseUnit.G), MenuPlanIngredient.of("김치", 100, BaseUnit.G),
            MenuPlanIngredient.of("계란", 1, BaseUnit.EA), MenuPlanIngredient.of("대파", 30, BaseUnit.G),
            MenuPlanIngredient.of("식용유", 10, BaseUnit.ML));

    // 검증·재시도를 거치는 바깥 게이트웨이
    @Autowired
    private LlmGateway llmGateway;

    // 실제 OpenAI를 부르는 안쪽 게이트웨이. 첫 시도만 틀린 답으로 바꾸고 재시도는 실제로 부른다
    @MockitoSpyBean
    private SpringAiLlmGateway springAiLlmGateway;

    @Autowired
    private MenuPlanPrompt menuPlanPrompt;

    @Autowired
    private ProductService productService;

    private Map<String, BaseUnit> ingredientUnits;
    private String systemPrompt;

    @BeforeEach
    void setUp() {
        ingredientUnits = productService.findIngredientUnits();
        assertThat(ingredientUnits).as("로컬 DB에 상품 시드가 없음")
                .containsEntry("대파", BaseUnit.G).containsEntry("계란", BaseUnit.EA).containsEntry("밥", BaseUnit.G);
        systemPrompt = menuPlanPrompt.systemPrompt(ingredientUnits, null);
    }

    @Test
    void 검증에_걸린_뒤_고칠_점을_붙인_재시도는_계란을_개수로_고쳐_통과한다() {
        // given: 계란 EA에 그램으로 생각한 120을 적었다
        MenuPlan gramsAsEach = shopping("김치볶음밥", 1, null, replace("계란", 120, BaseUnit.EA));

        // when
        int fixed = retryRepeatedly("계란 개수", gramsAsEach, "ingredient.amount", eachWithin("계란"), EACH_REPEATS);

        // then
        assertThat(fixed).isEqualTo(EACH_REPEATS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPlans")
    void 검증_실패_유형마다_고칠_점을_붙인_재시도가_알려_준_문제를_고친다(String label, MenuPlan invalidPlan, String expectedField,
                                                       Predicate<MenuPlan> fixed) {
        // when
        int fixedCount = retryRepeatedly(label, invalidPlan, expectedField, fixed, CASE_REPEATS);

        // then
        assertThat(fixedCount).isEqualTo(CASE_REPEATS);
    }

    // 유형마다 "알려 준 문제가 고쳐졌는가"를 따로 본다. 그 재료를 아예 빼도 알려 준 문제는 사라진 것으로 본다
    private static Stream<Arguments> invalidPlans() {
        return Stream.of(
                Arguments.of("단위가 목록과 다름 (대파 1BUNCH)",
                        shopping("김치볶음밥", 1, null, replace("대파", 1, BaseUnit.BUNCH)), "ingredient.unitMismatch",
                        each("대파", ingredient -> ingredient.unit() == BaseUnit.G)),
                Arguments.of("양이 0 (계란 0EA)",
                        shopping("김치볶음밥", 1, null, replace("계란", 0, BaseUnit.EA)), "ingredient.amount",
                        each("계란", ingredient -> ingredient.amount() != null && ingredient.amount() >= 1)),
                Arguments.of("그램 상한 초과 (밥 200000G)",
                        shopping("김치볶음밥", 1, null, replace("밥", 200_000, BaseUnit.G)), "ingredient.amount",
                        each("밥", ingredient -> ingredient.amount() != null && ingredient.amount() <= 100_000)),
                Arguments.of("같은 재료 합친 양 초과 (계란 40개 + 40개)",
                        shopping("김치볶음밥", 1, null, withExtra(MenuPlanIngredient.of("계란", 40, BaseUnit.EA),
                                replace("계란", 40, BaseUnit.EA))), "merged.ingredient.amount",
                        (Predicate<MenuPlan>) plan -> plan.ingredients().stream()
                                .filter(ingredient -> ingredient.name().equals("계란"))
                                .mapToInt(ingredient -> ingredient.amount() == null ? 0 : ingredient.amount()).sum() <= 60),
                Arguments.of("재료 없음",
                        shopping("김치볶음밥", 1, null, List.of()), "ingredients",
                        (Predicate<MenuPlan>) plan -> plan.ingredients() != null && !plan.ingredients().isEmpty()),
                Arguments.of("메뉴 이름 없음",
                        shopping("", 1, null, VALID_INGREDIENTS), "menuName",
                        (Predicate<MenuPlan>) plan -> StringUtils.hasText(plan.menuName())),
                Arguments.of("인원 범위 밖 (25명)",
                        shopping("김치볶음밥", 25, null, VALID_INGREDIENTS), "servings",
                        (Predicate<MenuPlan>) plan -> plan.servings() != null && plan.servings() >= 1 && plan.servings() <= 20),
                Arguments.of("예산 음수",
                        shopping("김치볶음밥", 1, -1_000L, VALID_INGREDIENTS), "budget",
                        (Predicate<MenuPlan>) plan -> plan.budget() == null || plan.budget() >= 0),
                Arguments.of("재료 이름 없음",
                        shopping("김치볶음밥", 1, null, withExtra(MenuPlanIngredient.of("", 100, BaseUnit.G), VALID_INGREDIENTS)),
                        "ingredient.name",
                        (Predicate<MenuPlan>) plan -> plan.ingredients().stream().allMatch(ingredient -> StringUtils.hasText(ingredient.name()))),
                Arguments.of("의도 없음",
                        MenuPlan.of(null, "김치볶음밥", 1, null, VALID_INGREDIENTS, List.of(), List.of()), "intent",
                        (Predicate<MenuPlan>) plan -> plan.intent() != null));
    }

    // 첫 시도는 틀린 답, 재시도는 실제 OpenAI로 repeats번 돌린다.
    // 매번 재시도 요청에만 고칠 점이 붙었는지 보고, 재시도 답에서 알려 준 문제가 고쳐졌는지(fixed)를 센다.
    // 재시도 답이 다른 칸에서 새로 검증에 걸려도 답은 OpenAI가 돌려준 그대로 받아서 본다
    private int retryRepeatedly(String label, MenuPlan invalidPlan, String expectedField, Predicate<MenuPlan> fixed,
                                int repeats) {
        assertThat(invalidPlan.findInvalidField(ingredientUnits)).as("틀린 답이 기대한 칸에 걸려야 함").contains(expectedField);
        assertThat(fixed.test(invalidPlan)).as("틀린 답은 고쳐지지 않은 것으로 판정돼야 함").isFalse();
        String expectedCorrection = invalidPlan.correction(expectedField, ingredientUnits);
        int fixedCount = 0;
        int newFailures = 0;
        List<String> results = new ArrayList<>();

        for (int i = 1; i <= repeats; i++) {
            clearInvocations(springAiLlmGateway);
            AtomicReference<MenuPlan> retriedAnswer = new AtomicReference<>();
            willReturn(LlmResult.of(invalidPlan, "forced-first-attempt", 0, 0))
                    .willAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        LlmResult<MenuPlan> real = (LlmResult<MenuPlan>) CALLS_REAL_METHODS.answer(invocation);
                        retriedAnswer.set(real.content());
                        return real;
                    })
                    .given(springAiLlmGateway).planMenu(anyString(), anyList(), anyString(), anyMap());

            try {
                llmGateway.planMenu(systemPrompt, List.of(), USER_TEXT, ingredientUnits);
            } catch (BusinessException ignored) {
                // 재시도 답이 다른 칸에서 새로 걸린 경우. 답은 retriedAnswer에 있다
            }

            ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
            then(springAiLlmGateway).should(times(2)).planMenu(prompts.capture(), anyList(), anyString(), anyMap());
            assertThat(prompts.getAllValues().get(0)).isEqualTo(systemPrompt);
            assertThat(prompts.getAllValues().get(1)).endsWith("## 직전 답에서 고칠 점\n" + expectedCorrection);

            MenuPlan retried = retriedAnswer.get();
            if (retried == null) {
                results.add(i + ": 재시도 답을 받지 못함");
                continue;
            }
            boolean isFixed = fixed.test(retried);
            Optional<String> otherFailure = retried.findInvalidField(ingredientUnits);
            fixedCount += isFixed ? 1 : 0;
            newFailures += isFixed && otherFailure.isPresent() ? 1 : 0;
            results.add(i + ": " + (isFixed ? "고침" : "못 고침")
                    + otherFailure.map(field -> " (다른 검증 실패 " + field + ")").orElse("")
                    + " | intent=" + retried.intent() + " menu=" + retried.menuName()
                    + " servings=" + retried.servings() + " budget=" + retried.budget()
                    + " 재료=" + retried.ingredients().stream()
                    .map(ingredient -> ingredient.name() + ingredient.amount() + ingredient.unit()).toList());
        }

        System.out.println("[재시도 검증] " + label + " → 고침 " + fixedCount + "/" + repeats
                + ", 고쳤지만 다른 칸에서 새로 걸림 " + newFailures
                + " | 고칠 점: " + expectedCorrection.substring(expectedCorrection.indexOf("- ") + 2));
        results.forEach(result -> System.out.println("[재시도 검증]   " + result));
        return fixedCount;
    }

    // 그 재료가 남아 있다면 모든 줄이 조건을 지켜야 한다. 빼면 알려 준 문제는 사라진 것으로 본다
    private static Predicate<MenuPlan> each(String name, Predicate<MenuPlanIngredient> rule) {
        return plan -> plan.ingredients().stream().filter(ingredient -> name.equals(ingredient.name())).allMatch(rule);
    }

    private static Predicate<MenuPlan> eachWithin(String name) {
        return each(name, ingredient -> ingredient.unit() == BaseUnit.EA
                && ingredient.amount() != null && ingredient.amount() >= 1 && ingredient.amount() <= 60);
    }

    private static MenuPlan shopping(String menuName, Integer servings, Long budget, List<MenuPlanIngredient> ingredients) {
        return MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, menuName, servings, budget, ingredients, List.of(), List.of());
    }

    // 맞는 재료 중 하나만 틀린 값으로 바꾼다
    private static List<MenuPlanIngredient> replace(String name, Integer amount, BaseUnit unit) {
        return VALID_INGREDIENTS.stream()
                .map(ingredient -> ingredient.name().equals(name) ? MenuPlanIngredient.of(name, amount, unit) : ingredient)
                .toList();
    }

    private static List<MenuPlanIngredient> withExtra(MenuPlanIngredient extra, List<MenuPlanIngredient> ingredients) {
        List<MenuPlanIngredient> withExtra = new ArrayList<>(ingredients);
        withExtra.add(extra);
        return withExtra;
    }
}
