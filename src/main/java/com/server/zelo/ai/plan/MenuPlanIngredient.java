package com.server.zelo.ai.plan;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import java.util.Map;
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

    public static MenuPlanIngredient of(String name, Integer amount, BaseUnit unit) {
        return new MenuPlanIngredient(name, amount, unit);
    }

    // 목록에 없는 재료는 오류가 아니다. 견적 단계에서 "상품 없음"으로 빠진다
    public void validate(Map<String, BaseUnit> ingredientUnits) {
        if (!StringUtils.hasText(name) || amount == null || unit == null || amount <= 0 || amount > MAX_AMOUNTS.get(unit)) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        BaseUnit expectedUnit = ingredientUnits.get(name);
        if (expectedUnit != null && expectedUnit != unit) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }
}
