package com.server.zelo.domain.user.service;

import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.request.CreateSocialUserRequest;
import com.server.zelo.domain.user.dto.request.SocialLoginRequest;
import com.server.zelo.domain.user.dto.response.ExistingMemberResult;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.dto.response.NewMemberResult;
import com.server.zelo.domain.user.dto.response.SocialLoginResult;
import com.server.zelo.domain.user.entity.SocialAccount;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserRole;
import com.server.zelo.domain.user.social.SocialLoginClient;
import com.server.zelo.domain.user.social.SocialProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SocialCommandService {

    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final UserAddressService userAddressService;
    private final RefreshTokenService refreshTokenService;
    private final SocialAccountService socialAccountService;
    private final List<SocialLoginClient> socialLoginClients;
    private final SocialSignupTicketService socialSignupTicketService;

    public SocialProfile consumeSignupTicket(String signupToken) {
        return socialSignupTicketService.consume(signupToken);
    }

    @Transactional
    public LoginResult signup(CreateSocialUserRequest request, SocialProfile profile) {
        // 이름이 없거나 50자를 넘으면 가입 저장에서 실패해서 보정한 이름을 씀
        User socialUser = userService.createSocialUser(profile.email(), profile.displayName(), request.phoneNumber());
        socialAccountService.createSocialAccount(socialUser, profile.provider(), profile.providerId());

        // 가입 시 받은 주소는 기본 배송지로 저장하고, 받는 사람은 일단 가입자 본인으로 함
        userAddressService.createUserAddress(
                socialUser, request.place(), socialUser.getName(), socialUser.getPhoneNumber(),
                request.zipCode(), request.addresses(), true);

        return loginWithSocial(socialUser.getId(), socialUser.getRole());
    }

    public SocialLoginResult login(String provider, SocialLoginRequest request) {
        // 우리가 제공하는 소셜 로그인 provider인지 확인
        SocialProvider socialProvider;
        try {
            socialProvider = SocialProvider.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_SOCIAL_PROVIDER);
        }

        // socialLoginClients를 통해 사용자 정보를 받음
        SocialProfile profile = findClient(socialProvider)
                .getProfile(request.code(), request.redirectUri(), request.codeVerifier());

        // 가입한 이력이 있다면 로그인 토큰을 만들어서 줌
        Optional<SocialAccount> socialAccount = socialAccountService.findByProviderAndProviderId(profile.provider(), profile.providerId());
        if (socialAccount.isPresent()){
            LoginResult result = loginWithSocial(socialAccount.get().getUser().getId(), socialAccount.get().getUser().getRole());
            return new ExistingMemberResult(result.accessToken(), result.refreshToken());
        }

        // 가입한 이력이 없다면 이메일이 필요하고 유효한지 확인
        profile.validateForSignup();

        // 우리 서버에서 동일한 이메일이 가입된적 있는지 확인
        if (userService.existsByEmail(profile.email())){
            throw new BusinessException(ErrorCode.SOCIAL_EMAIL_CONFLICT);
        }

        // 회원가입 시 사용할 정보를 30분 저장
        String signupToken = socialSignupTicketService.issue(profile);
        // 가입 화면에 미리 채워 주는 이름과 실제로 저장되는 이름이 같도록 같은 값을 씀
        return new NewMemberResult(signupToken, profile.email(), profile.displayName());
    }

    private LoginResult loginWithSocial(Long userId, UserRole role) {
        String accessToken = jwtUtil.createAccessToken(userId, role);
        String refreshToken = jwtUtil.createRefreshToken(userId, role);
        // 저장하지 않으면 재발급과 로그아웃, 다른 기기 로그인 감지가 이 토큰으로는 동작하지 않음
        refreshTokenService.saveRefreshToken(userId, refreshToken);
        return LoginResult.of(accessToken, refreshToken);
    }

    // provider에 적합한 socialLoginClients 를 가져옴
    private SocialLoginClient findClient(SocialProvider socialProvider) {
        return socialLoginClients.stream()
                .filter(client -> client.getProvider() == socialProvider)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_SOCIAL_PROVIDER) );
    }
}
