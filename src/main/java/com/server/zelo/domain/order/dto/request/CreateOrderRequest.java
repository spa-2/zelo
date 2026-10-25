package com.server.zelo.domain.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotNull
        @NotBlank
        @Size(min = 1, max = 100)
        String quoteId,
        @NotNull
        @NotBlank
        String confirmationId
) {
}
