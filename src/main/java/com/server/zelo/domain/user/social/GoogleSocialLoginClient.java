package com.server.zelo.domain.user.social;

import com.server.zelo.domain.user.entity.SocialProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "zelo.social.mode", havingValue = "real")
public class GoogleSocialLoginClient implements SocialLoginClient {
    @Override
    public SocialProvider getProvider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    public SocialProfile getProfile(String code, String redirectUri, String codeVerifier) {
        return null;
    }
}
