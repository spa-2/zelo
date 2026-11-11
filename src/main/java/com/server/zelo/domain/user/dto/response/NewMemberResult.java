package com.server.zelo.domain.user.dto.response;

// 소셜 로그인 이후 최초 가입자를 위한 응답
public record NewMemberResult(
        String signupTicket,
        String email,
        String name
) implements SocialLoginResult {
}
