package com.server.zelo.domain.user.dto.response;

import java.time.LocalDateTime;

public record CreateUserResponse(
        Long userId,
        String email,
        String name,
        String place,
        String addresses,
        String zipCode,
        LocalDateTime createdAt
) {

    public static CreateUserResponse of(Long userId, String email, String name, String place,
                                        String addresses, String zipCode, LocalDateTime createdAt) {
        return new CreateUserResponse(userId, email, name, place, addresses, zipCode, createdAt);
    }
}
