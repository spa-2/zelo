package com.server.zelo.domain.user.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
import com.server.zelo.domain.user.dto.request.LoginRequest;
import com.server.zelo.domain.user.dto.response.CreateUserResponse;
import com.server.zelo.domain.user.dto.response.LoginResponse;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.service.UserFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserFacade userFacade;
    @Value("${jwt.refresh-expiration}")
    private long refreshTokenExpiration;
    private static final String REFRESH_TOKEN_COOKIE = "refreshToken";


    @PostMapping("/api/users")
    public ResponseEntity<ApiResponse<CreateUserResponse>> createUserWithAddress(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(userFacade.createUserWithAddress(request)));
    }

    @PostMapping("/api/users/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = userFacade.login(request);

        // Todo Secure, SameSite는 프론트 확정 후 추가
        // 프론트가 정해지기 전이라 JS에서 못 읽게 HttpOnly만 적용함
        ResponseCookie refreshTokenCookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, result.refreshToken())
                .httpOnly(true)
                .path("/")
                .maxAge(Duration.ofMillis(refreshTokenExpiration))
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .body(ApiResponse.ok(LoginResponse.from(result)));
    }
}
