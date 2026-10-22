package com.server.zelo.domain.user.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.auth.RefreshTokenCookieFactory;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
import com.server.zelo.domain.user.dto.request.LoginRequest;
import com.server.zelo.domain.user.dto.response.CreateUserResponse;
import com.server.zelo.domain.user.dto.response.LoginResponse;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.service.RefreshTokenService;
import com.server.zelo.domain.user.service.UserCommandService;
import com.server.zelo.domain.user.service.UserFacade;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static com.server.zelo.common.auth.RefreshTokenCookieFactory.REFRESH_TOKEN_COOKIE;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserFacade userFacade;
    private final UserCommandService userCommandService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;

    @PostMapping("/api/users")
    public ResponseEntity<ApiResponse<CreateUserResponse>> createUserWithAddress(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(userFacade.createUserWithAddress(request)));
    }

    @PostMapping("/api/users/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        // 락이 필요 없는 요청은 Facade를 거치지 않고 Command를 바로 호출함
        // 이메일 단위 락을 걸면 남의 이메일로 로그인 요청을 계속 보내 정상 사용자를 막을 수 있고, 동시에 로그인해도 마지막 저장값이 남을 뿐 문제가 없음
        LoginResult result = userCommandService.login(request);

        ResponseCookie refreshTokenCookie = refreshTokenCookieFactory.create(result.refreshToken());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .body(ApiResponse.ok(LoginResponse.from(result)));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(@Auth AuthUser authUser) {
        // 리프레시 토큰 삭제 하나뿐이라 조율할 게 없어서 Command 없이 Service를 바로 호출함
        refreshTokenService.deleteRefreshToken(authUser.id());

        // 서버에서 지운 뒤 브라우저의 쿠키도 같이 지움 (속성이 다르면 다른 쿠키로 취급되어 안 지워져서 로그인 때와 같은 속성으로 만듦)
        ResponseCookie expiredCookie = refreshTokenCookieFactory.expire();

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .build();
    }

    @PostMapping("/api/auth/reissue")
    public ResponseEntity<ApiResponse<LoginResponse>> reissue(
            @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        LoginResult result;
        try {
            result = userCommandService.reissue(refreshToken);
        } catch (BusinessException e) {
            // 락 대기같은 예외가 아닌 비즈니스 에외가 발생했다면 만료된 토큰, 옛날 토큰, 탈취 토큰 중 하나라서 인증에 실패한 쿠키를 브라우저에서 지우게 응답
            if (e.getErrorCode().getStatus() == HttpStatus.UNAUTHORIZED) {
                response.addHeader(HttpHeaders.SET_COOKIE,  refreshTokenCookieFactory.expire().toString());
            }
            throw e;
        }

        // 로그인과 마찬가지로 리프레스 토큰으로 새로운 토큰을 받을 때 엑세스와 리프레스 모두 갱신 후 전달
        ResponseCookie refreshTokenCookie = refreshTokenCookieFactory.create(result.refreshToken());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .body(ApiResponse.ok(LoginResponse.from(result)));
    }

}
