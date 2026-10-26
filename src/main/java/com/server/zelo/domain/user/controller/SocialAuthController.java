package com.server.zelo.domain.user.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.RefreshTokenCookieFactory;
import com.server.zelo.domain.user.dto.request.CreateSocialUserRequest;
import com.server.zelo.domain.user.dto.request.SocialLoginRequest;
import com.server.zelo.domain.user.dto.response.*;
import com.server.zelo.domain.user.service.SocialCommandService;
import com.server.zelo.domain.user.service.SocialFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SocialAuthController {

    private final SocialFacade socialFacade;
    private final SocialCommandService socialCommandService;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;

    @PostMapping("/api/auth/social/signup")
    public ResponseEntity<ApiResponse<LoginResponse>> createSocialUserWithAddress(
            @Valid @RequestBody CreateSocialUserRequest request
            ){
        // 소셜 회원가입 후 jwt 토큰 생성
        LoginResult result = socialFacade.createSocialUserWithAddress(request);

        ResponseCookie refreshTokenCookie = refreshTokenCookieFactory.create(result.refreshToken());

        // 가입과 동시에 로그인
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .body(ApiResponse.ok(LoginResponse.from(result)));
    }

    @PostMapping("/api/auth/social/{provider}/login")
    public ResponseEntity<ApiResponse<SocialLoginResponse>> socialLogin(
            @PathVariable String provider, @Valid @RequestBody SocialLoginRequest request){
        SocialLoginResult loginResult = socialCommandService.login(provider, request);

        return switch (loginResult){
            // sealed를 사용함으로 하나의 케이스라도 빠지면 예는
            case ExistingMemberResult result -> {
                ResponseCookie refreshTokenCookie = refreshTokenCookieFactory.create(result.refreshToken());
                yield ResponseEntity.status(HttpStatus.OK)
                        .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                        .body(ApiResponse.ok(SocialLoginResponse.from(result)));
            }

            case NewMemberResult result ->
                    ResponseEntity.ok(ApiResponse.ok(SocialLoginResponse.from(result)));
        };

    }

}
