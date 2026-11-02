package com.server.zelo.domain.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateOrderConfirmationRequest(
        @NotNull
        @Positive
        Long roomId,
        @NotNull
        @NotBlank
        @Size(min = 1, max = 100)
        // 견적 ID는 서버가 만든 UUID라 영문·숫자·-·_만 받음. 줄바꿈·NUL 같은 제어 문자가 로그와 감사 로그에 못 들어가게
        @Pattern(regexp = "^[A-Za-z0-9_-]+$")
        String quoteId
) {
}
