package com.server.zelo.domain.chat.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 견적 카드 예산 후보의 [바꾸기] 요청. 상품 하나만 더 싼 후보로 바꾼다
public record ChooseBudgetOptionRequest(
        @NotBlank
        String quoteId, // 화면이 보고 있던 카드의 견적 ID. 지금 견적과 다르면 바꾸지 않는다
        @NotNull
        @Min(1)
        Long productId // 카드 budgetOptions[].productId (지금 담긴 상품)
) {
}
