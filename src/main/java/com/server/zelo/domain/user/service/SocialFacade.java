package com.server.zelo.domain.user.service;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.user.dto.request.CreateSocialUserRequest;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.social.SocialProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SocialFacade {

    private final SocialCommandService socialCommandService;
    private final LockDistributeService lockDistributeService;

    public LoginResult createSocialUserWithAddress(CreateSocialUserRequest request) {
        SocialProfile profile = socialCommandService.consumeSignupTicket(request.signupToken());
        return lockDistributeService.execute(LockKey.createUser(User.normalizeEmail(profile.email())),
                () -> socialCommandService.signup(request, profile)
        );
    }
}
