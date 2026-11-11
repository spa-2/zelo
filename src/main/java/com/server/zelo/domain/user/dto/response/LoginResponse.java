package com.server.zelo.domain.user.dto.response;

public record LoginResponse(
        String accessToken
) {

    public static LoginResponse from(LoginResult result) {
        return new LoginResponse(result.accessToken());
    }
}
