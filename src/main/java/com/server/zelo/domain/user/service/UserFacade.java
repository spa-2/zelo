package com.server.zelo.domain.user.service;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.user.dto.request.CreateUserAddressRequest;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
import com.server.zelo.domain.user.dto.response.CreateUserAddressResponse;
import com.server.zelo.domain.user.dto.response.CreateUserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserFacade {

    private final UserCommandService userCommandService;
    private final LockDistributeService lockDistributeService;


    // @Transactional은 Command에 있고, 락이 커밋 이후에 풀리도록 Facade에서는 락만 검
    public CreateUserResponse createUserWithAddress(CreateUserRequest request) {
        return lockDistributeService.execute(LockKey.createUser(request.email()),
                () -> userCommandService.createUserWithAddress(request));
    }

    // 같은 사용자의 배송지 등록이 동시에 들어와도 기본 배송지가 하나만 남도록 사용자 단위로 락을 잡음
    public CreateUserAddressResponse createUserAddress(Long userId, CreateUserAddressRequest request) {
        return lockDistributeService.execute(LockKey.createUserAddress(userId),
                () -> userCommandService.createUserAddress(userId, request));
    }

}
