package com.server.zelo.ai.llm;

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

    public static MenuPlanIngredient of(String name, Integer amount, BaseUnit unit) {
        return new MenuPlanIngredient(name, amount, unit);
    }

    // 목록에 없는 재료는 오류가 아니다. 견적 단계에서 "상품 없음"으로 빠진다
    public void validate(Map<String, BaseUnit> ingredientUnits) {
        if (!StringUtils.hasText(name) || amount == null || amount <= 0 || unit == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        BaseUnit expectedUnit = ingredientUnits.get(name);
        if (expectedUnit != null && expectedUnit != unit) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }
}
