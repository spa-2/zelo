package com.server.zelo.ai.plan;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

// LLM이 사용자 말을 정리한 장보기 메모. 가격·상품은 없고, 견적은 견적 도메인이 이 메모를 받아 서버에서 만든다
public record MenuPlan(
        MenuPlanIntent intent,
        String menuName,
        Integer servings, // 수량
        Long budget, // 원. 말하지 않았으면 null
        List<MenuPlanIngredient> ingredients,
        List<Keyword> conditions, // 상품을 고를 때 쓰는 조건 키워드
        List<String> unmatchedConditions // 키워드로 표현할 수 없는 조건. 버리지 않고 사용자 말 그대로 둔다
) {

    private static final int MIN_SERVINGS = 1;
    private static final int MAX_SERVINGS = 20;
    private static final int MAX_INGREDIENTS = 20;
    private static final long NO_BUDGET = 0L;

    public MenuPlan {
        // OpenAI 엄격 모드(Structured Outputs)는 모든 칸에 값을 넣게 해서 "예산 없음"을 null로 받을 수 없다.
        // 그래서 프롬프트로 0을 받게 하고 여기서 null로 바꾼다
        if (budget != null && budget == NO_BUDGET) {
            budget = null;
        }
    }

    public static MenuPlan of(MenuPlanIntent intent, String menuName, Integer servings, Long budget,
                              List<MenuPlanIngredient> ingredients, List<Keyword> conditions,
                              List<String> unmatchedConditions) {
        return new MenuPlan(intent, menuName, servings, budget, ingredients, conditions, unmatchedConditions);
    }

    // LLM이 준 값은 믿지 않는다. 장보기 요청이 아니면 재료가 없어도 정상이다
    public void validate(Map<String, BaseUnit> ingredientUnits) {
        if (intent == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        // 견적 수정은 무엇을 바꿀지 이 메모가 아니라 수정 계획(QuoteEditPlan)이 정하므로, 일반 질문처럼 나머지 칸을 보지 않는다
        if (intent == MenuPlanIntent.GENERAL_QUESTION || intent == MenuPlanIntent.QUOTE_EDIT) {
            return;
        }
        if (!StringUtils.hasText(menuName)) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        if (servings == null || servings < MIN_SERVINGS || servings > MAX_SERVINGS) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        if (budget != null && budget < 0) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        if (ingredients == null || ingredients.isEmpty() || ingredients.size() > MAX_INGREDIENTS) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        ingredients.forEach(ingredient -> ingredient.validate(ingredientUnits));
        // 견적은 이름이 같은 재료를 양을 합쳐 한 줄로 고른다(QuoteCommandService.select).
        // 줄마다 상한 안이어도 합친 양이 넘을 수 있어(쪽파 8단 + 8단), 합친 양도 같은 기준으로 본다
        Map<String, MenuPlanIngredient> merged = new LinkedHashMap<>();
        ingredients.forEach(ingredient -> merged.merge(ingredient.name(), ingredient, MenuPlanIngredient::plus));
        merged.values().forEach(ingredient -> ingredient.validate(ingredientUnits));
    }
}
