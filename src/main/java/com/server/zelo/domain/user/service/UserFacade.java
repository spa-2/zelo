package com.server.zelo.domain.user.service;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
import com.server.zelo.domain.user.dto.request.LoginRequest;
import com.server.zelo.domain.user.dto.response.CreateUserResponse;
import com.server.zelo.domain.user.dto.response.LoginResult;
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

    // 이메일 단위 락을 걸면 남의 이메일로 로그인 요청을 계속 보내 정상 사용자를 막을 수 있어서 락을 걸지 않음
    // 동시에 로그인해도 리프레시 토큰은 마지막 저장값이 남을 뿐 문제가 없음
    public LoginResult login(LoginRequest request) {
        return userCommandService.login(request);
    }
}
