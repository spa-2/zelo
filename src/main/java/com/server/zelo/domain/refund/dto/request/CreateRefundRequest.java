package com.server.zelo.domain.refund.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRefundRequest(
        @NotBlank @Size(max = 50) String reason
) {
}