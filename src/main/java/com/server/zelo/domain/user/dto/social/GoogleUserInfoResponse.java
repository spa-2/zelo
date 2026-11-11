package com.server.zelo.domain.user.dto.social;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
// accessToken를 통해 사용자의 정보를 요청, 기본적으로 제공하는 idToken에는 유저 정보가 없을 수 있기 때문
public record GoogleUserInfoResponse(
    // providerId에 사용하는 사용자 고유 식별자
    String sub,
    String email,
    @JsonProperty("email_verified")
    // 이메일 주소 인증 여부
    Boolean emailVerified,
    String name
) {

    public boolean isEmailVerified() {
        return emailVerified != null && emailVerified;
    }
}
