package com.server.zelo.domain.payment.dto.request;

import jakarta.validation.constraints.NotNull;

public record RetryPaymentRequest(
        @NotNull Long orderId
) {
}