package com.server.zelo.domain.product.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 입고 수량. 지금 재고에 더함
public record AddStockRequest(
        @NotNull
        @Min(1)
        Integer quantity
) {
}
