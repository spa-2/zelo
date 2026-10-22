package com.server.zelo.common.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 일반 로그인과 소셜 로그인 컨트롤러에서 공통으로 리프레시 토큰을 담아 전달하는 로직을 담당
@Component
public class RefreshTokenCookieFactory {

    private final Long refreshTokenExpiration;
    public static final String REFRESH_TOKEN_COOKIE = "refreshToken";
    public static final String REFRESH_TOKEN_COOKIE_PATH = "/api/auth/reissue";

    public RefreshTokenCookieFactory(@Value("${jwt.refresh-expiration}") Long refreshTokenExpiration) {
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    // Todo SameSite는 프론트 확정 후 추가
    public ResponseCookie create(String refreshToken) {
        return createCookie(refreshToken, Duration.ofMillis(refreshTokenExpiration));
    }

    public ResponseCookie expire() {
        return createCookie("", Duration.ZERO);
    }

    private ResponseCookie createCookie(String refreshToken, Duration expiration) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(true)
                .path(REFRESH_TOKEN_COOKIE_PATH)
                .maxAge(expiration)
                .build();
    }

}
