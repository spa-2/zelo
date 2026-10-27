package com.server.zelo.domain.product.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 설명을 안 보내면 설명이 지워짐
public record UpdateProductRequest(
        @NotNull
        @Min(0)
        Long price,
        @Size(max = 500)
        String description
) {
}