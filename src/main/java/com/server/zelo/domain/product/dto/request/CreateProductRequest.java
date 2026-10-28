package com.server.zelo.domain.product.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(
        // 상세 조회·수정 경로(/{productCode})에 그대로 들어가서 영문·숫자·-·_ 만
        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$")
        String productCode,
        @NotNull
        @Min(1)
        Long ingredientId,
        @NotBlank
        @Size(max = 50)
        String brand,
        @NotBlank
        @Size(max = 100)
        String name,
        @Size(max = 500)
        String description,
        @NotNull
        @Min(0)
        Long price,
        // 재료 기본 단위 기준 (김치 1kg이면 1000)
        @NotNull
        @Min(1)
        Integer contentAmount,
        @NotBlank
        @Size(max = 20)
        String unitLabel
) {
}