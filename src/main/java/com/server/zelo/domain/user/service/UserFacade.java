package com.server.zelo.domain.user.service;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
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

    public LoginResult reissue(String refreshToken) {
        // 리프레시 토큰이 유효한지 확인 후 userId를 가져옴
        Long userId = userCommandService.verifyTokenAndGetUserId(refreshToken);

        // 로그인과 달리 갱신은 락을 적용시켜 동시 요청을 막는다.
        // 만료된 엑세스 토큰과 저장된 리프레시 토큰을 모두 재발급 받음
        return lockDistributeService.execute(LockKey.tokenRotate(userId),
                () -> userCommandService.reissue(userId, refreshToken)
        );
    }
}
