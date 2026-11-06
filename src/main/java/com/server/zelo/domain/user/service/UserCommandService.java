package com.server.zelo.domain.user.service;

import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.user.dto.request.ResetPasswordRequest;
import com.server.zelo.domain.user.dto.request.CreateUserAddressRequest;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
import com.server.zelo.domain.user.dto.request.LoginRequest;
import com.server.zelo.domain.user.dto.response.CreateUserAddressResponse;
import com.server.zelo.domain.user.dto.response.CreateUserResponse;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.emailverification.service.EmailVerificationService;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import com.server.zelo.domain.user.entity.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserCommandService {

    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final BusinessMetrics businessMetrics;
    private final UserAddressService userAddressService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;
    private final EmailVerificationService emailVerificationService;

    // 유저와 배송지가 같이 저장되거나 같이 롤백되어야 해서 여기서 트랜잭션을 묶음
    @Transactional
    public CreateUserResponse createUserWithAddress(CreateUserRequest request) {

        validatePassword(request.password(), request.passwordConfirm());

        // 이메일 인증 티켓을 소비하는데 티켓이 없거나 만료됐거나 이메일이 다르면 예외를 던짐
        emailVerificationService.consumeTicket(request.email(),request.emailVerificationTicket(), EmailVerificationPurpose.SIGNUP);

        User user = userService.createUser(request.email(), request.password(), request.name(), request.phoneNumber());

        // 가입 시 받은 주소는 기본 배송지로 저장하고, 받는 사람은 일단 가입자 본인으로 함
        UserAddress userAddress = userAddressService.createUserAddress(
                user, request.place(), user.getName(), user.getPhoneNumber(),
                request.zipCode(), request.addresses(), true);

        businessMetrics.count(BusinessMetric.USER_CREATED, "method", "EMAIL");
        log.info("회원 가입 완료 userId={} method={}", user.getId(), "EMAIL");

        return CreateUserResponse.of(user.getId(), user.getEmail(), user.getName(), userAddress.getPlace(),
                userAddress.getAddress(), userAddress.getZipCode(), user.getCreatedAt());
    }

    public LoginResult login(LoginRequest request, String remoteAddr) {
        // 로그인 시 요청 이메일과 사용자의 공인 IP를 조합으로 시도 횟수를 증가시킨다.
        loginAttemptService.attempt(request.email(), remoteAddr);

        User user = userService.getUserByEmailAndPassword(request.email(), request.password());

        // 인증에 성공했다면 시도 횟수에서 제거
        loginAttemptService.deleteAttempt(request.email(), remoteAddr);

        String accessToken = jwtUtil.createAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtUtil.createRefreshToken(user.getId(), user.getRole());
        refreshTokenService.saveRefreshToken(user.getId(), refreshToken);

        businessMetrics.count(BusinessMetric.USER_LOGIN, "method", "EMAIL");
        log.info("로그인 성공 userId={} method={}", user.getId(), "EMAIL");

        return LoginResult.of(accessToken, refreshToken);
    }

    @Transactional
    public CreateUserAddressResponse createUserAddress(Long userId, CreateUserAddressRequest request) {
        User user = userService.getUser(userId);
        userAddressService.validatePlaceNotDuplicated(userId, request.place());

        // 기본 배송지는 회원마다 하나만 두므로, 새 배송지를 기본으로 정하면 기존 기본 배송지를 해제함
        if (request.isDefault()) {
            userAddressService.releaseDefaultAddress(userId);
        }

        UserAddress userAddress = userAddressService.createUserAddress(user, request.place(),
                request.recipientName(), request.recipientPhone(), request.zipCode(), request.address(),
                request.isDefault());
        return CreateUserAddressResponse.from(userAddress);
    }

    // 교체 자체는 Redis 안에서 원자적으로 처리되어서 락 없이 호출함
    public LoginResult reissue(String refreshToken) {
        Long userId = verifyTokenAndGetUserId(refreshToken);

        // role 변경 로직이 없어서 claim에서 role을 읽어서 가져온다. (변경된다면 재로그인 필요)
        UserRole role = jwtUtil.getUserRole(refreshToken);
        String newAccessToken = jwtUtil.createAccessToken(userId, role);
        String newRefreshToken = jwtUtil.createRefreshToken(userId, role);
        // 리프레스 토큰이 유효할때만 새로운 토큰으로 응답
        refreshTokenService.rotateToken(userId, refreshToken, newRefreshToken);

        // 로그인이 유지되는 동안 주기적으로 일어나서 로그는 남기지 않음 (재사용 감지 WARN은 RefreshTokenService가 남김)
        businessMetrics.count(BusinessMetric.TOKEN_REISSUED);

        return LoginResult.of(newAccessToken, newRefreshToken);
    }

    @Transactional
    public void validateResetPassword(ResetPasswordRequest request) {

        validatePassword(request.password(), request.passwordConfirm());

        // 패스워드 변경 티켓 소비
        emailVerificationService.consumeTicket(request.email(), request.emailVerificationTicket(), EmailVerificationPurpose.PASSWORD_RESET);

        // 유저를 찾고, 패스워드가 null인지 검증한다.
        User user = userService.getUserByEmail(request.email());
        if (user.isPasswordNull()) {
            throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_USER);
        }

        // 패스워드 변경(초기화)
        userService.resetPassword(user,request.password());

        // 리프레시 토큰 폐기
        refreshTokenService.deleteRefreshToken(user.getId());

        businessMetrics.count(BusinessMetric.USER_PASSWORD_RESET);
        log.info("비밀번호 재설정 완료 userId={}", user.getId());
    }

    private void validatePassword(String password, String passwordConfirm) {
        if (!password.equals(passwordConfirm)) {
            throw new BusinessException(ErrorCode.PASSWORD_CONFIRMATION_MISMATCH);
        }
    }

    private Long verifyTokenAndGetUserId(String refreshToken) {
        // 토큰이 유효하지 않거나 리프레시 토큰이 아니라면 예외
        if (!jwtUtil.validateToken(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        return jwtUtil.getUserId(refreshToken);
    }
}
