package com.server.zelo.domain.user.social;

import com.server.zelo.domain.user.entity.SocialProvider;

public interface SocialLoginClient {
    SocialProvider getProvider();
    SocialProfile getProfile(String code, String redirectUri, String codeVerifier);
}
