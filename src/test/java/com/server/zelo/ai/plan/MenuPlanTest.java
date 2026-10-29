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

    private static MenuPlan shopping(Integer servings, Long budget, List<MenuPlanIngredient> ingredients) {
        return MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", servings, budget, ingredients,
                List.of(Keyword.NOT_SPICY), List.of());
    }

    private static void assertInvalid(MenuPlan menuPlan) {
        assertThatThrownBy(() -> menuPlan.validate(INGREDIENT_UNITS))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
    }
}
