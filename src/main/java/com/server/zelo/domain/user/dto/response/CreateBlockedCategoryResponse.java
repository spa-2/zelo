package com.server.zelo.domain.user.dto.response;

import com.server.zelo.domain.user.entity.UserBlockedCategory;

public record CreateBlockedCategoryResponse(
        Long categoryId,
        String name
) {

    public static CreateBlockedCategoryResponse from(UserBlockedCategory blocked) {
        return new CreateBlockedCategoryResponse(blocked.getCategory().getId(), blocked.getCategory().getName());
    }
}