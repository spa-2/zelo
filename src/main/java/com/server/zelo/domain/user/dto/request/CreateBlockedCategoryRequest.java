package com.server.zelo.domain.user.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateBlockedCategoryRequest(
        @NotNull
        @Positive
        Long categoryId
) {
}