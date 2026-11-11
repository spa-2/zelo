package com.server.zelo.domain.product.dto.request;

import jakarta.validation.constraints.Size;

public record AutocompleteCondition(
        @Size(max = 50)
        String keyword
) {

    public AutocompleteCondition {
        keyword = keyword == null ? "" : keyword.strip();
    }
}