package com.server.zelo.common.auth;

import com.server.zelo.domain.user.entity.UserRole;

public record AuthUser(
        Long id,
        UserRole role
) {

    public static AuthUser of(Long id, UserRole role) {
        return new AuthUser(id, role);
    }
}
