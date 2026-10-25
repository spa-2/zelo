package com.server.zelo.domain.user.dto.response;

// 이미 가입한 사용자를 위한 응답
public record ExistingMemberResult(
        String accessToken,
        String refreshToken
) implements SocialLoginResult {
}