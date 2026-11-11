package com.server.zelo.ai.plan;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        if (findInvalidField(ingredientUnits).isPresent()) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }

    // 검증에 걸린 칸 이름. 같은 AI_009라도 어느 규칙에 걸렸는지 로그에 남기려고 validate와 나눴다
    public Optional<String> findInvalidField(Map<String, BaseUnit> ingredientUnits) {
        if (intent == null) {
            return Optional.of("intent");
        }
        // 견적 수정은 무엇을 바꿀지 이 메모가 아니라 수정 계획(QuoteEditPlan)이 정하므로, 일반·조회 질문처럼 나머지 칸을 보지 않는다
        if (intent == MenuPlanIntent.GENERAL_QUESTION || intent == MenuPlanIntent.LOOKUP_QUESTION
                || intent == MenuPlanIntent.QUOTE_EDIT) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(menuName)) {
            return Optional.of("menuName");
        }
        if (servings == null || servings < MIN_SERVINGS || servings > MAX_SERVINGS) {
            return Optional.of("servings");
        }
        if (budget != null && budget < 0) {
            return Optional.of("budget");
        }
        if (ingredients == null || ingredients.isEmpty() || ingredients.size() > MAX_INGREDIENTS) {
            return Optional.of("ingredients");
        }
        for (MenuPlanIngredient ingredient : ingredients) {
            Optional<String> invalidField = ingredient.findInvalidField(ingredientUnits);
            if (invalidField.isPresent()) {
                return invalidField;
            }
        }
        // 견적은 이름이 같은 재료를 양을 합쳐 한 줄로 고른다(QuoteCommandService.select).
        // 줄마다 상한 안이어도 합친 양이 넘을 수 있어(쪽파 8단 + 8단), 합친 양도 같은 기준으로 본다
        Map<String, MenuPlanIngredient> merged = new LinkedHashMap<>();
        ingredients.forEach(ingredient -> merged.merge(ingredient.name(), ingredient, MenuPlanIngredient::plus));
        for (MenuPlanIngredient ingredient : merged.values()) {
            Optional<String> invalidField = ingredient.findInvalidField(ingredientUnits);
            if (invalidField.isPresent()) {
                return Optional.of("merged." + invalidField.get());
            }
        }
        return Optional.empty();
    }

    // 검증에 걸린 칸을 LLM이 고칠 수 있게 무엇이 틀렸는지 적는다. 재시도 요청에만 붙이고 로그에는 남기지 않는다(재료 이름·양이 LLM 응답 값이라)
    // 재시도 요청에는 직전 답이 들어가지 않으므로 "나머지는 그대로"라고 하지 않고 처음부터 다시 답하게 한다
    public String correction(String field, Map<String, BaseUnit> ingredientUnits) {
        String detail = switch (field) {
            case "intent" -> "intent를 정해진 값 중 하나로 적어.";
            case "menuName" -> "SHOPPING_REQUEST면 menuName을 비우지 마.";
            case "servings" -> "servings는 %d부터 %d 사이로 적어.".formatted(MIN_SERVINGS, MAX_SERVINGS);
            case "budget" -> "budget은 0 이상으로 적어. 말하지 않았으면 0이야.";
            case "ingredients" -> "SHOPPING_REQUEST면 ingredients를 1개 이상 %d개 이하로 적어.".formatted(MAX_INGREDIENTS);
            default -> ingredientCorrection(field, ingredientUnits);
        };
        return "직전 답이 서버 검증에 걸렸어. 아래를 지켜서 처음부터 다시 답해.\n- " + detail;
    }

    // findInvalidField와 같은 순서로 다시 돌며, 그 칸에 처음 걸린 재료를 찾아 그 재료 기준으로 적는다
    private String ingredientCorrection(String field, Map<String, BaseUnit> ingredientUnits) {
        String mergedPrefix = "merged.";
        if (field.startsWith(mergedPrefix)) {
            String ingredientField = field.substring(mergedPrefix.length());
            Map<String, MenuPlanIngredient> merged = new LinkedHashMap<>();
            ingredients.forEach(ingredient -> merged.merge(ingredient.name(), ingredient, MenuPlanIngredient::plus));
            return merged.values().stream()
                    .filter(ingredient -> ingredient.findInvalidField(ingredientUnits).filter(ingredientField::equals).isPresent())
                    .findFirst()
                    .map(ingredient -> "같은 재료 %s를 여러 줄로 나눠 적었어. 한 줄로 합쳐서 적어. ".formatted(ingredient.name())
                            + ingredient.correction(ingredientField, ingredientUnits))
                    .orElse("같은 재료를 한 줄로 합쳐서 적어.");
        }
        return ingredients.stream()
                .filter(ingredient -> ingredient.findInvalidField(ingredientUnits).filter(field::equals).isPresent())
                .findFirst()
                .map(ingredient -> ingredient.correction(field, ingredientUnits))
                .orElse("재료 값을 재료 목록과 규칙에 맞게 다시 적어.");
    }
}
