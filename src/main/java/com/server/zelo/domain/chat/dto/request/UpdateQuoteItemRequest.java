package com.server.zelo.domain.chat.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 견적 카드의 ✕, −/+ 버튼 요청. quantity는 바꾼 뒤의 최종 수량이고 0이면 뺀다(1에서 −를 누르면 빠지도록).
// 더하는 양이 아니라 최종 수량이라 같은 요청이 두 번 와도 결과가 같다
public record UpdateQuoteItemRequest(
        @NotBlank
        String quoteId, // 화면이 보고 있던 카드의 견적 ID. 지금 견적과 다르면 고치지 않는다
        @NotNull
        @Min(1)
        Long productId,
        @NotNull
        @Min(0)
        @Max(10)
        Integer quantity
) {

    public boolean isRemove() {
        return quantity == 0;
    }
}
