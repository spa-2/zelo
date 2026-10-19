package com.server.zelo.domain.user.facade;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.user.dto.request.CreateUserRequest;
import com.server.zelo.domain.user.dto.response.CreateUserResponse;
import com.server.zelo.domain.user.service.UserCommandService;
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
}
