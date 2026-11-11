package com.server.zelo.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SocialLoginRequest(
        @NotBlank
        @Size(min = 1, max = 255)
        // authorization code : 토큰으로 교환하는 일회용 인증 코드
        String code,

        @NotBlank
        @Size(min = 1, max = 255)
        // code를 전달한 서버의 주소, 소셜 서버에서 탈취를 막기 위함
        String redirectUri,

        // 값이 없어도 됨
        @Size(min = 43, max = 128)
        String codeVerifier
) {
}
