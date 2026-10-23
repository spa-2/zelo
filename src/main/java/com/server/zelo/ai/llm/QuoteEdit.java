package com.server.zelo.ai.llm;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import java.util.Map;

// 견적 수정 하나. 엄격 모드는 모든 칸에 값을 넣게 해서, 그 동작에 쓰지 않는 칸은 0·빈 문자열로 받는다
public record QuoteEdit(
        QuoteEditAction action,
        Long productId, // REMOVE, CHANGE_QUANTITY: 현재 견적에서 고를 상품
        Integer quantity, // CHANGE_QUANTITY: 바꾼 뒤의 상품 개수
        String ingredientName, // ADD: 재료 이름 (ingredients.name)
        Integer amount, // ADD: 더할 양 (재료의 기본 단위 기준)
        BaseUnit unit // ADD: 재료의 기본 단위
) {

    public static QuoteEdit of(QuoteEditAction action, Long productId, Integer quantity,
                               String ingredientName, Integer amount, BaseUnit unit) {
        return new QuoteEdit(action, productId, quantity, ingredientName, amount, unit);
    }

    // productId가 현재 견적에 있는지는 견적을 아는 쪽(채팅)이 따로 확인한다
    public void validate(Map<String, BaseUnit> ingredientUnits) {
        if (action == null) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        switch (action) {
            case REMOVE -> validateProductId();
            case CHANGE_QUANTITY -> {
                validateProductId();
                if (quantity == null || quantity < 1) {
                    throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
                }
            }
            // 추가는 메뉴 계획의 재료 한 줄과 같은 규칙(이름·양·단위, 목록에 있는 재료는 단위 일치)으로 본다
            case ADD -> MenuPlanIngredient.of(ingredientName, amount, unit).validate(ingredientUnits);
        }
    }

    public boolean isProductEdit() {
        return action == QuoteEditAction.REMOVE || action == QuoteEditAction.CHANGE_QUANTITY;
    }

    private void validateProductId() {
        if (productId == null || productId < 1) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
    }
}
