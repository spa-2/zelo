package com.server.zelo.ai.plan;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.StringUtils;

public record MenuPlanIngredient(
        String name, // 재료 분류 이름 (ingredients.name)
        Integer amount, // 전체 인원분 양
        BaseUnit unit // 그 재료의 기본 단위. 상품의 content_amount와 같은 단위여야 수량을 계산할 수 있다
) {

    // 단위별 최대 양. G·ML은 견적이 수량을 int로 계산할 때(양 ÷ 상품 용량, 같은 재료 양 합치기) 넘치지 않게 막는다(100kg·100L).
    // EA·BUNCH는 LLM이 그램으로 생각한 숫자를 개수로 적는 실수(쪽파 15단)를 막는다. 최대 인원 20명 기준으로 EA는 계란 3개씩, BUNCH는 카드 버튼 최대 수량과 같게 둔다
    private static final Map<BaseUnit, Integer> MAX_AMOUNTS = Map.of(
            BaseUnit.G, 100_000,
            BaseUnit.ML, 100_000,
            BaseUnit.EA, 60,
            BaseUnit.BUNCH, 10);
    // #449에서 EA·BUNCH → G로 바꾼 채소. 개수로 세는 습관이 남아 LLM이 "오이 2개"를 2G로 적는다(오이무침 2인분 5회 중 1회, 2026-11-10).
    // G 상한만으로는 걸리지 않고 견적에서 1팩으로 올림되므로, 이 채소만 그램 최솟값을 둔다. 소금·마늘처럼 몇 그램만 쓰는 재료는 넣지 않는다
    private static final Set<String> GRAM_VEGETABLES = Set.of(
            "당근", "브로콜리", "파프리카", "양배추", "양파", "대파", "배추", "쪽파", "오이", "애호박", "단호박");
    private static final int MIN_VEGETABLE_GRAMS = 10;

    public static MenuPlanIngredient of(String name, Integer amount, BaseUnit unit) {
        return new MenuPlanIngredient(name, amount, unit);
    }

    // 같은 재료를 합칠 때 쓴다. 단위는 견적과 같게 앞 줄을 따른다(줄마다 상한 안이고 최대 20줄이라 int를 넘지 않는다)
    public MenuPlanIngredient plus(MenuPlanIngredient other) {
        return of(name, amount + other.amount, unit);
    }

    // 목록에 없는 재료는 오류가 아니다. 견적 단계에서 "상품 없음"으로 빠진다
    public void validate(Map<String, BaseUnit> ingredientUnits) {
        if (findInvalidField(ingredientUnits).isPresent()) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }

    // 검증에 걸린 칸 이름. 같은 AI_009라도 어느 규칙에 걸렸는지 로그에 남기려고 validate와 나눴다
    public Optional<String> findInvalidField(Map<String, BaseUnit> ingredientUnits) {
        if (!StringUtils.hasText(name)) {
            return Optional.of("ingredient.name");
        }
        if (unit == null) {
            return Optional.of("ingredient.unit");
        }
        if (amount == null || amount <= 0 || amount > MAX_AMOUNTS.get(unit)) {
            return Optional.of("ingredient.amount");
        }
        BaseUnit expectedUnit = ingredientUnits.get(name);
        if (expectedUnit != null && expectedUnit != unit) {
            return Optional.of("ingredient.unitMismatch");
        }
        if (isVegetableCountAsGrams()) {
            return Optional.of("ingredient.amount");
        }
        return Optional.empty();
    }

    // 검증에 걸린 칸(findInvalidField의 값)을 LLM이 다시 고칠 수 있게 무엇이 틀렸는지 한 문장으로 만든다.
    // 같은 질문만 다시 보내면 같은 실수를 되풀이해서(대파 BUNCH에 그램 30 → 재시도에 20) 재시도 요청에만 붙인다
    public String correction(String field, Map<String, BaseUnit> ingredientUnits) {
        return switch (field) {
            case "ingredient.name" -> "재료 name을 비우지 말고 재료 목록에 있는 이름으로 적어.";
            case "ingredient.unit", "ingredient.unitMismatch" -> ingredientUnits.containsKey(name)
                    ? "%s의 unit은 재료 목록에 적힌 %s로 적고, amount도 그 단위에 맞게 다시 적어."
                            .formatted(name, ingredientUnits.get(name))
                    : "%s의 unit을 G, ML, EA, BUNCH 중 하나로 적어.".formatted(name);
            case "ingredient.amount" -> amountCorrection();
            default -> "재료 값을 재료 목록과 규칙에 맞게 다시 적어.";
        };
    }

    private String amountCorrection() {
        if (amount == null || amount <= 0) {
            return "%s의 amount는 1 이상으로 적어.".formatted(name);
        }
        if (isVegetableCountAsGrams()) {
            return "%s의 unit은 G라서 amount는 개수가 아니라 그램이야. 지금 적은 값(%d)이 너무 작아. 개수로 생각했다면 그램으로 바꿔 %d 이상으로 다시 적어."
                    .formatted(name, amount, MIN_VEGETABLE_GRAMS);
        }
        int maxAmount = MAX_AMOUNTS.get(unit);
        if (unit == BaseUnit.EA || unit == BaseUnit.BUNCH) {
            String counter = unit == BaseUnit.EA ? "개" : "단";
            return "%s의 unit은 %s라서 amount는 그램이 아니라 사는 %s 수야. 지금 적은 값(%d)이 너무 커. %d 이하로 다시 적어."
                    .formatted(name, unit, counter, amount, maxAmount);
        }
        return "%s의 amount는 %s 기준인데 지금 적은 값(%d)이 너무 커. %d 이하로 다시 적어.".formatted(name, unit, amount, maxAmount);
    }

    private boolean isVegetableCountAsGrams() {
        return unit == BaseUnit.G && GRAM_VEGETABLES.contains(name) && amount != null && amount < MIN_VEGETABLE_GRAMS;
    }
}
