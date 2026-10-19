package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
import com.server.zelo.domain.user.dto.response.CreateUserResponse;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserCommandService {

    private final UserService userService;
    private final UserAddressService userAddressService;

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
}
