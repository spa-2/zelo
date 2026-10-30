package com.server.zelo.domain.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SocialLoginResponse(
        // 회원가입 필요 여부
        boolean signupRequired,
        // 최초 로그인이 아니면 토큰 전달
        String accessToken,
        // 최초 로그인이면 회원가입 티켓 전달
        String signupTicket,
        String email,
        String name
) {
    public static SocialLoginResponse from(ExistingMemberResult result) {
        return new SocialLoginResponse(false, result.accessToken(), null,null, null);
    }

    public static SocialLoginResponse from(NewMemberResult result) {
        return new SocialLoginResponse(true, null, result.signupTicket(), result.email(), result.name());
    }
}
