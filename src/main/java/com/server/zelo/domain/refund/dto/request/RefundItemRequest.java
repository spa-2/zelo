package com.server.zelo.domain.refund.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RefundItemRequest(
        @NotNull Long orderItemId,
        @NotNull @Min(1) Integer quantity
) {
}