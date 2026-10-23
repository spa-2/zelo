package com.server.zelo.domain.user.service;

import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.domain.user.dto.request.CreateSocialUserRequest;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.social.SocialProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SocialCommandService {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final UserAddressService userAddressService;
    private final RefreshTokenService refreshTokenService;
    private final SocialAccountService socialAccountService;
    private final SocialSignupTicketService socialSignupTicketService;

    public SocialProfile consumeSignupTicket(String signupToken) {
        return socialSignupTicketService.consume(signupToken);
    }

    @Transactional
    public LoginResult signup(CreateSocialUserRequest request, SocialProfile profile) {
        User socialUser = userService.createSocialUser(profile.email(), profile.name(), request.phoneNumber());
        socialAccountService.createSocialAccount(socialUser, profile.provider(), profile.providerId());

        // 가입 시 받은 주소는 기본 배송지로 저장하고, 받는 사람은 일단 가입자 본인으로 함
        userAddressService.createUserAddress(
                socialUser, request.place(), socialUser.getName(), socialUser.getPhoneNumber(),
                request.zipCode(), request.addresses(), true);

        return loginWithSocial(socialUser.getId());
    }

    private LoginResult loginWithSocial(Long userId) {
        String accessToken = jwtUtil.createAccessToken(userId);
        String refreshToken = jwtUtil.createRefreshToken(userId);
        // 저장하지 않으면 재발급과 로그아웃, 다른 기기 로그인 감지가 이 토큰으로는 동작하지 않음
        refreshTokenService.saveRefreshToken(userId, refreshToken);
        return LoginResult.of(accessToken, refreshToken);
    }
}
