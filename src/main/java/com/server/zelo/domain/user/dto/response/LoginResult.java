package com.server.zelo.domain.user.dto.response;

// 리프레시 토큰은 응답 본문이 아니라 쿠키로 내려가서, 본문용 LoginResponse와 분리함
public record LoginResult(
        String accessToken,
        String refreshToken
) {

    public static LoginResult of(String accessToken, String refreshToken) {
        return new LoginResult(accessToken, refreshToken);
    }
}
