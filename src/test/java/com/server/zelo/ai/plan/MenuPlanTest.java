package com.server.zelo.ai.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MenuPlanTest {

    private static final Map<String, BaseUnit> INGREDIENT_UNITS = Map.of(
            "김치", BaseUnit.G,
            "계란", BaseUnit.EA);
    private static final List<MenuPlanIngredient> INGREDIENTS = List.of(
            MenuPlanIngredient.of("김치", 400, BaseUnit.G),
            MenuPlanIngredient.of("계란", 4, BaseUnit.EA));

    @Test
    void 올바른_장보기_메모는_검증을_통과한다() {
        // given
        MenuPlan menuPlan = shopping(4, 30_000L, INGREDIENTS);

        // when & then
        assertThatCode(() -> menuPlan.validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 장보기가_아닌_말은_재료가_없어도_검증을_통과한다() {
        // given
        MenuPlan menuPlan = MenuPlan.of(MenuPlanIntent.GENERAL_QUESTION, "", 0, 0L, List.of(), List.of(), List.of());

        // when & then
        assertThatCode(() -> menuPlan.validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 조회_질문은_메뉴와_재료가_비어_있어도_검증을_통과한다() {
        // given
        MenuPlan menuPlan = MenuPlan.of(MenuPlanIntent.LOOKUP_QUESTION, "", 0, 0L, List.of(), List.of(), List.of());

        // when & then
        assertThatCode(() -> menuPlan.validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 견적_수정_요청은_메뉴와_재료가_비어_있어도_검증을_통과한다() {
        // given: 무엇을 바꿀지는 견적 수정 도구가 정한다
        MenuPlan menuPlan = MenuPlan.of(MenuPlanIntent.QUOTE_EDIT, "", 0, 0L, List.of(), List.of(), List.of());

        // when & then
        assertThatCode(() -> menuPlan.validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 예산_0은_예산을_말하지_않은_것으로_보고_null로_바꾼다() {
        // when
        MenuPlan menuPlan = shopping(4, 0L, INGREDIENTS);

        // then
        assertThat(menuPlan.budget()).isNull();
    }

    @Test
    void 목록에_없는_재료는_검증_오류가_아니다() {
        // given: 견적 단계에서 "상품 없음"으로 빠진다
        MenuPlan menuPlan = shopping(4, null, List.of(MenuPlanIngredient.of("트러플", 10, BaseUnit.G)));

        // when & then
        assertThatCode(() -> menuPlan.validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 요청_종류가_없으면_실패한다() {
        // given
        MenuPlan menuPlan = MenuPlan.of(null, "김치볶음밥", 4, null, INGREDIENTS, List.of(), List.of());

        // when & then
        assertInvalid(menuPlan);
    }

    @Test
    void 장보기_요청인데_메뉴_이름이_비어_있으면_실패한다() {
        // given
        MenuPlan menuPlan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, " ", 4, null, INGREDIENTS, List.of(), List.of());

        // when & then
        assertInvalid(menuPlan);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 21})
    void 인원이_없거나_1명에서_20명_사이가_아니면_실패한다(Integer servings) {
        // given
        MenuPlan menuPlan = shopping(servings, null, INGREDIENTS);

        // when & then
        assertInvalid(menuPlan);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void 인원이_1명과_20명이면_통과한다(int servings) {
        // given
        MenuPlan menuPlan = shopping(servings, null, INGREDIENTS);

        // when & then
        assertThatCode(() -> menuPlan.validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 예산이_음수면_실패한다() {
        // given
        MenuPlan menuPlan = shopping(4, -1L, INGREDIENTS);

        // when & then
        assertInvalid(menuPlan);
    }

    @Test
    void 장보기_요청인데_재료가_없으면_실패한다() {
        // when & then
        assertInvalid(shopping(4, null, List.of()));
        assertInvalid(shopping(4, null, null));
    }

    @Test
    void 재료가_20개를_넘으면_실패한다() {
        // given
        List<MenuPlanIngredient> ingredients = Collections.nCopies(21, MenuPlanIngredient.of("김치", 100, BaseUnit.G));

        // when & then
        assertInvalid(shopping(4, null, ingredients));
    }

    @Test
    void 재료가_20개면_통과한다() {
        // given
        List<MenuPlanIngredient> ingredients = Collections.nCopies(20, MenuPlanIngredient.of("김치", 100, BaseUnit.G));

        // when & then
        assertThatCode(() -> shopping(4, null, ingredients).validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 재료_이름이_비어_있으면_실패한다() {
        // when & then
        assertInvalid(shopping(4, null, List.of(MenuPlanIngredient.of(" ", 400, BaseUnit.G))));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -100})
    void 재료_양이_없거나_0_이하면_실패한다(Integer amount) {
        // when & then
        assertInvalid(shopping(4, null, List.of(MenuPlanIngredient.of("김치", amount, BaseUnit.G))));
    }

    // 목록에 없는 재료 이름을 써서 단위 비교 없이 단위별 상한만 본다
    @ParameterizedTest
    @CsvSource({"G, 100000", "ML, 100000", "EA, 60", "BUNCH, 10"})
    void 재료_양이_단위별_상한이면_통과한다(BaseUnit unit, int maxAmount) {
        // given
        MenuPlan menuPlan = shopping(4, null, List.of(MenuPlanIngredient.of("새 재료", maxAmount, unit)));

        // when & then
        assertThatCode(() -> menuPlan.validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"G, 100001", "ML, 100001", "EA, 61", "BUNCH, 11"})
    void 재료_양이_단위별_상한을_넘으면_실패한다(BaseUnit unit, int amount) {
        // given: BUNCH는 LLM이 쪽파 15g을 15단으로 적는 실수를 막는다
        MenuPlan menuPlan = shopping(4, null, List.of(MenuPlanIngredient.of("새 재료", amount, unit)));

        // when & then
        assertInvalid(menuPlan);
    }

    @Test
    void 같은_재료를_나눠_적어도_합친_양이_상한을_넘으면_실패한다() {
        // given: 줄마다는 상한(10단) 안이지만 견적이 합치면 16단이 된다
        List<MenuPlanIngredient> ingredients = List.of(
                MenuPlanIngredient.of("쪽파", 8, BaseUnit.BUNCH),
                MenuPlanIngredient.of("쪽파", 8, BaseUnit.BUNCH));

        // when & then
        assertInvalid(shopping(4, null, ingredients));
    }

    @Test
    void 같은_재료를_나눠_적고_합친_양이_상한이면_통과한다() {
        // given
        MenuPlan menuPlan = shopping(4, null, List.of(
                MenuPlanIngredient.of("쪽파", 4, BaseUnit.BUNCH),
                MenuPlanIngredient.of("쪽파", 6, BaseUnit.BUNCH)));

        // when & then
        assertThatCode(() -> menuPlan.validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 재료_단위가_없으면_실패한다() {
        // when & then
        assertInvalid(shopping(4, null, List.of(MenuPlanIngredient.of("김치", 400, null))));
    }

    @Test
    void 목록에_있는_재료의_단위가_기본_단위와_다르면_실패한다() {
        // given: 김치의 기본 단위는 G인데 EA(포기 수)로 적었다. 상품 용량(g)과 비교할 수 없다
        MenuPlan menuPlan = shopping(4, null, List.of(MenuPlanIngredient.of("김치", 1, BaseUnit.EA)));

        // when & then
        assertInvalid(menuPlan);
    }

    @ParameterizedTest
    @CsvSource({"오이, 2", "양파, 1", "대파, 9"})
    void 그램_단위_채소를_10그램_미만으로_적으면_개수를_적은_것으로_보고_실패한다(String name, int amount) {
        // given: 실측에서 오이무침 2인분의 오이 2개를 2G로 적었다
        MenuPlan menuPlan = shopping(2, null, List.of(MenuPlanIngredient.of(name, amount, BaseUnit.G)));

        // when & then
        assertThat(menuPlan.findInvalidField(Map.of(name, BaseUnit.G))).contains("ingredient.amount");
    }

    @Test
    void 그램_단위_채소가_10그램이거나_몇_그램만_쓰는_양념이면_통과한다() {
        // given: 쪽파 10G(고명)는 실측에 나온 정상 값이고, 소금·마늘은 채소 최솟값 대상이 아니다
        MenuPlan menuPlan = shopping(2, null, List.of(
                MenuPlanIngredient.of("쪽파", 10, BaseUnit.G),
                MenuPlanIngredient.of("소금", 3, BaseUnit.G),
                MenuPlanIngredient.of("마늘", 5, BaseUnit.G)));
        Map<String, BaseUnit> units = Map.of("쪽파", BaseUnit.G, "소금", BaseUnit.G, "마늘", BaseUnit.G);

        // when & then
        assertThatCode(() -> menuPlan.validate(units)).doesNotThrowAnyException();
    }

    @Test
    void 그램_단위_채소에_개수를_적으면_그램으로_바꿔_다시_적으라고_고칠_점을_만든다() {
        // given
        MenuPlan plan = shopping(2, null, List.of(MenuPlanIngredient.of("오이", 2, BaseUnit.G)));
        Map<String, BaseUnit> units = Map.of("오이", BaseUnit.G);

        // when
        String correction = plan.correction(plan.findInvalidField(units).orElseThrow(), units);

        // then
        assertThat(correction).contains("오이의 unit은 G라서 amount는 개수가 아니라 그램이야. 지금 적은 값(2)이 너무 작아. 개수로 생각했다면 그램으로 바꿔 10 이상으로 다시 적어.");
    }

    @Test
    void 검증을_통과하면_걸린_칸이_없다() {
        // given
        MenuPlan menuPlan = shopping(4, 30_000L, INGREDIENTS);

        // when
        Optional<String> invalidField = menuPlan.findInvalidField(INGREDIENT_UNITS);

        // then
        assertThat(invalidField).isEmpty();
    }

    @Test
    void 검증에_걸리면_걸린_칸_이름을_돌려준다() {
        // given
        MenuPlan noIntent = MenuPlan.of(null, "김치볶음밥", 4, null, INGREDIENTS, List.of(), List.of());
        MenuPlan noMenuName = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, " ", 4, null, INGREDIENTS, List.of(), List.of());

        // when & then
        assertThat(noIntent.findInvalidField(INGREDIENT_UNITS)).contains("intent");
        assertThat(noMenuName.findInvalidField(INGREDIENT_UNITS)).contains("menuName");
        assertThat(shopping(21, null, INGREDIENTS).findInvalidField(INGREDIENT_UNITS)).contains("servings");
        assertThat(shopping(4, -1L, INGREDIENTS).findInvalidField(INGREDIENT_UNITS)).contains("budget");
        assertThat(shopping(4, null, List.of()).findInvalidField(INGREDIENT_UNITS)).contains("ingredients");
    }

    @Test
    void 재료가_검증에_걸리면_재료의_걸린_칸_이름을_돌려준다() {
        // given
        MenuPlan noName = shopping(4, null, List.of(MenuPlanIngredient.of(" ", 400, BaseUnit.G)));
        MenuPlan noUnit = shopping(4, null, List.of(MenuPlanIngredient.of("김치", 400, null)));
        MenuPlan zeroAmount = shopping(4, null, List.of(MenuPlanIngredient.of("김치", 0, BaseUnit.G)));
        MenuPlan wrongUnit = shopping(4, null, List.of(MenuPlanIngredient.of("계란", 400, BaseUnit.G)));

        // when & then
        assertThat(noName.findInvalidField(INGREDIENT_UNITS)).contains("ingredient.name");
        assertThat(noUnit.findInvalidField(INGREDIENT_UNITS)).contains("ingredient.unit");
        assertThat(zeroAmount.findInvalidField(INGREDIENT_UNITS)).contains("ingredient.amount");
        assertThat(wrongUnit.findInvalidField(INGREDIENT_UNITS)).contains("ingredient.unitMismatch");
    }

    @Test
    void 합친_재료_양이_상한을_넘으면_merged가_붙은_칸_이름을_돌려준다() {
        // given: 쪽파 8단 + 8단 = 16단으로 BUNCH 상한 10단을 넘는다
        MenuPlan menuPlan = shopping(4, null, List.of(
                MenuPlanIngredient.of("쪽파", 8, BaseUnit.BUNCH),
                MenuPlanIngredient.of("쪽파", 8, BaseUnit.BUNCH)));

        // when
        Optional<String> invalidField = menuPlan.findInvalidField(INGREDIENT_UNITS);

        // then
        assertThat(invalidField).contains("merged.ingredient.amount");
    }

    private static MenuPlan shopping(Integer servings, Long budget, List<MenuPlanIngredient> ingredients) {
        return MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", servings, budget, ingredients,
                List.of(Keyword.NOT_SPICY), List.of());
    }

    private static void assertInvalid(MenuPlan menuPlan) {
        assertThatThrownBy(() -> menuPlan.validate(INGREDIENT_UNITS))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
    }

    @Test
    void 개수_단위_재료에_그램_같은_큰_수를_적으면_사는_개수로_다시_적으라고_고칠_점을_만든다() {
        // given
        MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 1, null,
                List.of(MenuPlanIngredient.of("김치", 100, BaseUnit.G), MenuPlanIngredient.of("대파", 30, BaseUnit.BUNCH)),
                List.of(), List.of());
        Map<String, BaseUnit> units = Map.of("김치", BaseUnit.G, "대파", BaseUnit.BUNCH);

        // when
        String correction = plan.correction(plan.findInvalidField(units).orElseThrow(), units);

        // then
        assertThat(correction)
                .startsWith("직전 답이 서버 검증에 걸렸어")
                .contains("대파의 unit은 BUNCH라서 amount는 그램이 아니라 사는 단 수야. 지금 적은 값(30)이 너무 커. 10 이하로 다시 적어.");
    }

    @Test
    void 단위가_재료_목록과_다르면_목록의_단위로_다시_적으라고_고칠_점을_만든다() {
        // given
        MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 1, null,
                List.of(MenuPlanIngredient.of("계란", 100, BaseUnit.G)), List.of(), List.of());
        Map<String, BaseUnit> units = Map.of("계란", BaseUnit.EA);

        // when
        String correction = plan.correction(plan.findInvalidField(units).orElseThrow(), units);

        // then
        assertThat(correction).contains("계란의 unit은 재료 목록에 적힌 EA로 적고, amount도 그 단위에 맞게 다시 적어.");
    }

    @Test
    void 같은_재료를_나눠_적어_합친_양이_넘으면_한_줄로_합치라고_고칠_점을_만든다() {
        // given: 줄마다 8단은 상한(10) 안이지만 합치면 16단이다
        MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 1, null,
                List.of(MenuPlanIngredient.of("쪽파", 8, BaseUnit.BUNCH), MenuPlanIngredient.of("쪽파", 8, BaseUnit.BUNCH)),
                List.of(), List.of());
        Map<String, BaseUnit> units = Map.of("쪽파", BaseUnit.BUNCH);

        // when
        String correction = plan.correction(plan.findInvalidField(units).orElseThrow(), units);

        // then
        assertThat(correction).contains("같은 재료 쪽파를 여러 줄로 나눠 적었어. 한 줄로 합쳐서 적어.")
                .contains("지금 적은 값(16)이 너무 커");
    }

    @Test
    void 장보기_요청에_재료가_없으면_재료를_적으라고_고칠_점을_만든다() {
        // given
        MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 1, null, List.of(), List.of(), List.of());

        // when
        String correction = plan.correction(plan.findInvalidField(Map.of()).orElseThrow(), Map.of());

        // then
        assertThat(correction).contains("SHOPPING_REQUEST면 ingredients를 1개 이상 20개 이하로 적어.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "intent | intent를 정해진 값 중 하나로 적어.",
            "menuName | SHOPPING_REQUEST면 menuName을 비우지 마.",
            "servings | servings는 1부터 20 사이로 적어.",
            "budget | budget은 0 이상으로 적어. 말하지 않았으면 0이야.",
    })
    void 메뉴_인원_예산_의도_칸에_걸리면_그_칸의_규칙을_고칠_점으로_만든다(String field, String expected) {
        // given
        MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 1, null,
                List.of(MenuPlanIngredient.of("김치", 100, BaseUnit.G)), List.of(), List.of());

        // when
        String correction = plan.correction(field, Map.of("김치", BaseUnit.G));

        // then
        assertThat(correction).isEqualTo("직전 답이 서버 검증에 걸렸어. 아래를 지켜서 처음부터 다시 답해.\n- " + expected);
    }

    @Test
    void 재료_이름이_비면_목록의_이름으로_적으라고_고칠_점을_만든다() {
        // given
        MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 1, null,
                List.of(MenuPlanIngredient.of("", 100, BaseUnit.G)), List.of(), List.of());

        // when
        String correction = plan.correction(plan.findInvalidField(Map.of()).orElseThrow(), Map.of());

        // then
        assertThat(correction).endsWith("- 재료 name을 비우지 말고 재료 목록에 있는 이름으로 적어.");
    }

    @Test
    void 목록에_없는_재료의_단위가_비면_정해진_단위_중_하나로_적으라고_고칠_점을_만든다() {
        // given
        MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 1, null,
                List.of(MenuPlanIngredient.of("트러플", 10, null)), List.of(), List.of());

        // when
        String correction = plan.correction(plan.findInvalidField(Map.of()).orElseThrow(), Map.of());

        // then
        assertThat(correction).endsWith("- 트러플의 unit을 G, ML, EA, BUNCH 중 하나로 적어.");
    }

    @Test
    void 양이_0이면_1_이상으로_적으라고_고칠_점을_만든다() {
        // given
        MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 1, null,
                List.of(MenuPlanIngredient.of("계란", 0, BaseUnit.EA)), List.of(), List.of());
        Map<String, BaseUnit> units = Map.of("계란", BaseUnit.EA);

        // when
        String correction = plan.correction(plan.findInvalidField(units).orElseThrow(), units);

        // then
        assertThat(correction).endsWith("- 계란의 amount는 1 이상으로 적어.");
    }

    @Test
    void 그램_재료가_상한을_넘으면_그_단위_기준으로_줄이라고_고칠_점을_만든다() {
        // given
        MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 1, null,
                List.of(MenuPlanIngredient.of("밥", 200_000, BaseUnit.G)), List.of(), List.of());
        Map<String, BaseUnit> units = Map.of("밥", BaseUnit.G);

        // when
        String correction = plan.correction(plan.findInvalidField(units).orElseThrow(), units);

        // then
        assertThat(correction).endsWith("- 밥의 amount는 G 기준인데 지금 적은 값(200000)이 너무 커. 100000 이하로 다시 적어.");
    }

    @Test
    void 개수_재료가_상한을_넘으면_사는_개수로_다시_적으라고_고칠_점을_만든다() {
        // given
        MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 1, null,
                List.of(MenuPlanIngredient.of("계란", 100, BaseUnit.EA)), List.of(), List.of());
        Map<String, BaseUnit> units = Map.of("계란", BaseUnit.EA);

        // when
        String correction = plan.correction(plan.findInvalidField(units).orElseThrow(), units);

        // then
        assertThat(correction).endsWith("- 계란의 unit은 EA라서 amount는 그램이 아니라 사는 개 수야. 지금 적은 값(100)이 너무 커. 60 이하로 다시 적어.");
    }

    @Test
    void 걸린_재료를_찾지_못하면_재료를_규칙에_맞게_다시_적으라고_고칠_점을_만든다() {
        // given: 재료는 모두 맞는데 재료 칸 이름만 들어온 경우
        MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 1, null,
                List.of(MenuPlanIngredient.of("김치", 100, BaseUnit.G)), List.of(), List.of());
        Map<String, BaseUnit> units = Map.of("김치", BaseUnit.G);

        // when
        String correction = plan.correction("ingredient.amount", units);
        String mergedCorrection = plan.correction("merged.ingredient.amount", units);

        // then
        assertThat(correction).endsWith("- 재료 값을 재료 목록과 규칙에 맞게 다시 적어.");
        assertThat(mergedCorrection).endsWith("- 같은 재료를 한 줄로 합쳐서 적어.");
    }

    @Test
    void 재료_칸에_없는_이름이_들어오면_재료를_규칙에_맞게_다시_적으라고_고칠_점을_만든다() {
        // given
        MenuPlanIngredient ingredient = MenuPlanIngredient.of("김치", 100, BaseUnit.G);

        // when
        String correction = ingredient.correction("ingredient.other", Map.of("김치", BaseUnit.G));

        // then
        assertThat(correction).isEqualTo("재료 값을 재료 목록과 규칙에 맞게 다시 적어.");
    }
}
