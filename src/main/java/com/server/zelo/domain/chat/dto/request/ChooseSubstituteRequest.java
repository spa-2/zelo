package com.server.zelo.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 견적 카드 대체 후보의 [좋아요](accept=true)·[빼기](accept=false) 요청.
// 견적 담당이 후보를 재료 이름으로 찾으므로 상품 ID가 아니라 재료 이름을 받는다
public record ChooseSubstituteRequest(
        @NotBlank
        String quoteId, // 화면이 보고 있던 카드의 견적 ID. 지금 견적과 다르면 고르지 않는다
        @NotBlank
        String ingredientName, // 카드 substitutes[].ingredientName
        @NotNull
        Boolean accept
) {
}
