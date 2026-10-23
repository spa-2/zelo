package com.server.zelo.domain.user.service;

import com.server.zelo.domain.user.entity.SocialAccount;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.repository.SocialAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SocialAccountService {
    private final SocialAccountRepository socialAccountRepository;

    public void createSocialAccount(User socialUser, SocialProvider provider, String providerId) {
        socialAccountRepository.save(SocialAccount.of(socialUser, provider, providerId));
    }
}
