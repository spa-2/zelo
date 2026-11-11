package com.server.zelo.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePaymentMethodRequest(
        @NotBlank
        @Size(max = 200)
        String billingKey
) {
}