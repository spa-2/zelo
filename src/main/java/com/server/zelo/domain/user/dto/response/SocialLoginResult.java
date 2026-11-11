package com.server.zelo.domain.user.dto.response;

// 소셜 로그인시 최초 로그인과 일반 로그인 2가지의 클래스로 상속을 제한
public sealed interface SocialLoginResult
        permits ExistingMemberResult, NewMemberResult {
}