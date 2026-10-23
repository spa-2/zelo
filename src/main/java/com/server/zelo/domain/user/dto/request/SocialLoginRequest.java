package com.server.zelo.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SocialLoginRequest(
        @NotBlank
        @Size(min = 1, max = 255)
        String code,
        @NotBlank
        @Size(min = 1, max = 255)
        String redirectUri,
        // 값이 없어도 됨
        @Size(min = 43, max = 128)
        String codeVerifier
) {
}
