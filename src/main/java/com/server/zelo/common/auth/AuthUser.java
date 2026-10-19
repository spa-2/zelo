package com.server.zelo.common.auth;

public record AuthUser(
        Long id
) {

    public static AuthUser of(Long id) {
        return new AuthUser(id);
    }
}
