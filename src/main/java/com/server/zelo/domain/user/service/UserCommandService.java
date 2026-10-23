package com.server.zelo.domain.user.service;

import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.request.CreateUserAddressRequest;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
import com.server.zelo.domain.user.dto.request.LoginRequest;
import com.server.zelo.domain.user.dto.response.CreateUserAddressResponse;
import com.server.zelo.domain.user.dto.response.CreateUserResponse;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserCommandService {

    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final UserAddressService userAddressService;
    private final RefreshTokenService refreshTokenService;

    // 유저와 배송지가 같이 저장되거나 같이 롤백되어야 해서 여기서 트랜잭션을 묶음
    @Transactional
    public CreateUserResponse createUserWithAddress(CreateUserRequest request) {

        if (!request.password().equals(request.passwordConfirm())) {
            throw new BusinessException(ErrorCode.PASSWORD_CONFIRMATION_MISMATCH);
        }

        User user = userService.createUser(request.email(), request.password(), request.name(), request.phoneNumber());

        // 가입 시 받은 주소는 기본 배송지로 저장하고, 받는 사람은 일단 가입자 본인으로 함
        UserAddress userAddress = userAddressService.createUserAddress(
                user, request.place(), user.getName(), user.getPhoneNumber(),
                request.zipCode(), request.addresses(), true);

        return CreateUserResponse.of(user.getId(), user.getEmail(), user.getName(), userAddress.getPlace(),
                userAddress.getAddress(), userAddress.getZipCode(), user.getCreatedAt());
    }

    // 리프레시 토큰은 Redis에 저장되므로 DB 변경은 없고 조회만 하는 트랜잭션으로 둠
    @Transactional(readOnly = true)
    public LoginResult login(LoginRequest request) {
        User user = userService.getUserByEmailAndPassword(request.email(), request.password());

        String accessToken = jwtUtil.createAccessToken(user.getId());
        String refreshToken = jwtUtil.createRefreshToken(user.getId());
        refreshTokenService.saveRefreshToken(user.getId(), refreshToken);

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

        String newAccessToken = jwtUtil.createAccessToken(userId);
        String newRefreshToken = jwtUtil.createRefreshToken(userId);
        // 리프레스 토큰이 유효할때만 새로운 토큰으로 응답
        refreshTokenService.rotateToken(userId, refreshToken, newRefreshToken);
        return LoginResult.of(newAccessToken, newRefreshToken);
    }

    private Long verifyTokenAndGetUserId(String refreshToken) {
        // 토큰이 유효하지 않거나 리프레시 토큰이 아니라면 예외
        if (!jwtUtil.validateToken(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        return jwtUtil.getUserId(refreshToken);
    }


}
