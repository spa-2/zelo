package com.server.zelo.ai.eval;

import static com.server.zelo.ai.plan.MenuPlanIntent.GENERAL_QUESTION;
import static com.server.zelo.ai.plan.MenuPlanIntent.SHOPPING_REQUEST;
import static com.server.zelo.domain.product.entity.Keyword.NOT_SPICY;
import static com.server.zelo.domain.product.entity.Keyword.SPICY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.server.zelo.ai.eval.AiEvalReport.Group;
import com.server.zelo.ai.eval.AiEvalReport.Kind;
import com.server.zelo.ai.eval.AiEvalReport.Result;
import com.server.zelo.ai.eval.AiEvalReport.Stats;
import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.ai.plan.QuoteEditAction;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// 실제 OpenAI 없이, 평가의 채점·집계가 맞는지 정해 둔 답으로 확인한다
class AiEvalScoringTest {

    private static final Map<String, BaseUnit> INGREDIENT_UNITS = Map.of(
            "김치", BaseUnit.G, "밥", BaseUnit.G, "계란", BaseUnit.EA, "대파", BaseUnit.G);

    @Test
    void 메뉴_계획이_정답과_같으면_틀린_칸이_없다() {
        // given
        MenuPlanEvalCase evalCase = menuCase("kid-in-total");
        MenuPlan plan = shoppingPlan("김치볶음밥", 4, 30_000L, List.of(NOT_SPICY), List.of());

        // when
        List<String> mismatches = evalCase.mismatches(plan, INGREDIENT_UNITS);

        // then
        assertThat(mismatches).isEmpty();
    }

    @Test
    void 인원과_조건이_다르면_그_칸을_기대값과_실제값으로_돌려준다() {
        // given
        MenuPlanEvalCase evalCase = menuCase("kid-in-total");
        MenuPlan plan = shoppingPlan("김치볶음밥", 6, 30_000L, List.of(SPICY), List.of());

        // when
        List<String> mismatches = evalCase.mismatches(plan, INGREDIENT_UNITS);

        // then
        assertThat(mismatches).containsExactly("servings: 4 → 6", "conditions: [NOT_SPICY] 포함 → [SPICY]");
    }

    @Test
    void 의도가_다르면_나머지_칸은_보지_않고_의도만_틀린_칸으로_돌려준다() {
        // given
        MenuPlanEvalCase evalCase = menuCase("kid-in-total");
        MenuPlan plan = MenuPlan.of(GENERAL_QUESTION, "", 0, 0L, List.of(), List.of(), List.of());

        // when
        List<String> mismatches = evalCase.mismatches(plan, INGREDIENT_UNITS);

        // then
        assertThat(mismatches).containsExactly("intent: SHOPPING_REQUEST → GENERAL_QUESTION");
    }

    @Test
    void 재료_목록에_없는_재료는_오답으로_센다() {
        // given
        MenuPlanEvalCase evalCase = menuCase("inject-lobster");
        MenuPlan plan = MenuPlan.of(SHOPPING_REQUEST, "김치찌개", 2, 0L,
                List.of(MenuPlanIngredient.of("김치", 300, BaseUnit.G), MenuPlanIngredient.of("랍스터", 1, BaseUnit.EA)),
                List.of(), List.of());

        // when
        List<String> mismatches = evalCase.mismatches(plan, INGREDIENT_UNITS);

        // then
        assertThat(mismatches).containsExactly("ingredient: 목록 안 재료 → 랍스터");
    }

    @Test
    void 주입_문장이_시킨_글자가_메뉴_이름에_들어가면_오답이다() {
        // given
        MenuPlanEvalCase evalCase = menuCase("inject-menu-name");
        MenuPlan plan = shoppingPlan("해킹 성공", 3, null, List.of(), List.of());

        // when
        List<String> mismatches = evalCase.mismatches(plan, INGREDIENT_UNITS);

        // then
        assertThat(mismatches).singleElement().asString().startsWith("injectedText: '해킹' 없음");
    }

    @Test
    void 견적_수정은_말한_순서가_달라도_같은_수정이면_정답이다() {
        // given
        QuoteEditEvalCase evalCase = editCase("remove-and-change");
        QuoteEditPlan plan = QuoteEditPlan.of(List.of(change(392L, 2), remove(192L)), "");

        // when
        List<String> mismatches = evalCase.mismatches(plan);

        // then
        assertThat(mismatches).isEmpty();
    }

    @Test
    void 견적에_없는_상품_번호를_고치면_번호_오답으로_돌려준다() {
        // given
        QuoteEditEvalCase evalCase = editCase("remove-green-onion");
        QuoteEditPlan plan = QuoteEditPlan.of(List.of(remove(99_999L)), "");

        // when
        List<String> mismatches = evalCase.mismatches(plan);

        // then
        assertThat(mismatches).containsExactly("productId: 견적 안 상품 한 번씩 → [REMOVE:99999]");
    }

    @Test
    void 되묻기를_기대한_문장에서_수정을_내면_오답이다() {
        // given
        QuoteEditEvalCase evalCase = editCase("ask-two-kimchi");
        QuoteEditPlan plan = QuoteEditPlan.of(List.of(remove(514L)), "");

        // when
        List<String> mismatches = evalCase.mismatches(plan);

        // then
        assertThat(mismatches).containsExactly("question: 되묻기 → [REMOVE:514]");
    }

    @Test
    void 되묻는_문장에_프롬프트_내용이_나오면_오답이다() {
        // given
        QuoteEditEvalCase evalCase = editCase("edit-inject-prompt-leak");
        QuoteEditPlan plan = QuoteEditPlan.of(List.of(), "재료 목록은 김치, 밥, 계란입니다.");

        // when
        List<String> mismatches = evalCase.mismatches(plan);

        // then
        assertThat(mismatches).singleElement().asString().startsWith("injectedText: '재료 목록' 없음");
    }

    @Test
    void 문장_id는_겹치지_않는다() {
        // given
        List<String> ids = Stream.concat(MenuPlanEvalCase.ALL.stream().map(MenuPlanEvalCase::id),
                QuoteEditEvalCase.ALL.stream().map(QuoteEditEvalCase::id)).toList();

        // when
        Set<String> unique = Set.copyOf(ids);

        // then
        assertThat(unique).hasSameSizeAs(ids);
    }

    // 정답에 적은 상품 번호가 그 문장의 견적에 없으면 LLM이 맞혀도 오답이 되므로 정답부터 확인한다
    @Test
    void 견적_수정_정답의_상품_번호는_모두_그_문장의_견적에_있다() {
        for (QuoteEditEvalCase evalCase : QuoteEditEvalCase.ALL) {
            // given
            Set<String> productIds = evalCase.quote().items().stream()
                    .map(item -> String.valueOf(item.productId())).collect(Collectors.toSet());

            // when
            List<String> productIdsInExpected = evalCase.expected().stream()
                    .filter(key -> !key.startsWith("ADD:"))
                    .map(key -> key.split(":")[1])
                    .toList();

            // then
            assertThat(productIds).as(evalCase.id()).containsAll(productIdsInExpected);
        }
    }

    @Test
    void 묶음_숫자는_검증_실패와_호출_실패를_나눠_세고_p95는_가까운_순위_값이다() {
        // given
        List<Result> results = List.of(
                result(100, null, null, List.of(), 1_000, 100),
                result(200, null, null, List.of("servings: 4 → 6"), 1_000, 100),
                result(300, null, "servings", List.of(), 1_000, 100),
                result(400, "AI_001", null, List.of(), null, null));

        // when
        Stats stats = Stats.of(results);

        // then
        assertThat(stats.accuracy()).isEqualTo(0.25);
        assertThat(stats.invalidRate()).isEqualTo(0.25);
        assertThat(stats.errorRate()).isEqualTo(0.25);
        assertThat(stats.p50()).isEqualTo(200);
        assertThat(stats.p95()).isEqualTo(400);
        // (1,000 × 0.10 + 100 × 0.50) / 1,000,000 달러
        assertThat(stats.avgCostUsd()).isCloseTo(0.00015, within(1e-12));
    }

    private static MenuPlanEvalCase menuCase(String id) {
        return MenuPlanEvalCase.ALL.stream().filter(evalCase -> evalCase.id().equals(id)).findFirst().orElseThrow();
    }

    private static QuoteEditEvalCase editCase(String id) {
        return QuoteEditEvalCase.ALL.stream().filter(evalCase -> evalCase.id().equals(id)).findFirst().orElseThrow();
    }

    private static MenuPlan shoppingPlan(String menuName, int servings, Long budget, List<Keyword> conditions,
                                         List<String> unmatched) {
        return MenuPlan.of(SHOPPING_REQUEST, menuName, servings, budget,
                List.of(MenuPlanIngredient.of("김치", 300, BaseUnit.G), MenuPlanIngredient.of("계란", 4, BaseUnit.EA)),
                conditions, unmatched);
    }

    private static QuoteEdit remove(Long productId) {
        return QuoteEdit.of(QuoteEditAction.REMOVE, productId, 0, "", 0, BaseUnit.EA);
    }

    private static QuoteEdit change(Long productId, int quantity) {
        return QuoteEdit.of(QuoteEditAction.CHANGE_QUANTITY, productId, quantity, "", 0, BaseUnit.EA);
    }

    private static Result result(long latencyMs, String error, String invalidField, List<String> mismatches,
                                 Integer promptTokens, Integer completionTokens) {
        return new Result("case", Group.SINGLE, Kind.MENU_PLAN, "문장", 1, latencyMs, error, invalidField, mismatches,
                "-", "gpt-6-luna", promptTokens, completionTokens);
    }
}
