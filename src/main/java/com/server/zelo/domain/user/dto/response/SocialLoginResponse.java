package com.server.zelo.domain.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SocialLoginResponse(
        boolean signupRequired,
        String accessToken,
        String signupToken,
        String email,
        String name
) {
}
