package com.server.zelo.domain.user.dto.social;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoUserInfoResponse(
        // providerId에 사용하는 사용자 고유 식별자
        Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
        ) {
    //2계층
    @JsonIgnoreProperties(ignoreUnknown = true)
     public record KakaoAccount(
            String email,
            // 현재 이메일이 유효한지
            @JsonProperty("is_email_valid")
            Boolean emailValid,
            // 인증을 완료 하였는지
            @JsonProperty("is_email_verified")
            Boolean emailVerified,
            @JsonProperty("profile")
            Profile profile
    ){
        // 3계층, 기존 2계층에 있는 닉네임은 설전될 수 있다고 하여 3계층 정보를 사용
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Profile(String nickname){ }
    }

    public String getProviderId(){
        return id.toString();
    }

    // 동의 항목을 허용하지 않으면 kakao_account, profile이 응답에서 빠질 수 있어 null을 허용
    public String getEmail(){
        return kakaoAccount == null ? null : kakaoAccount.email();
    }

    public String getNickname(){
        if (kakaoAccount == null || kakaoAccount.profile() == null) {
            return null;
        }
        return kakaoAccount.profile().nickname();
    }

    public boolean isEmailVerified(){
        return kakaoAccount != null
                && Boolean.TRUE.equals(kakaoAccount.emailValid())
                && Boolean.TRUE.equals(kakaoAccount.emailVerified());
    }
}
