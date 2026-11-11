package com.server.zelo.domain.user.dto.response;

import com.server.zelo.domain.user.entity.UserBlockedCategory;

public record GetBlockedCategoryResponse(
        Long categoryId,
        String name
) {

    public static GetBlockedCategoryResponse from(UserBlockedCategory blocked) {
        return new GetBlockedCategoryResponse(blocked.getCategory().getId(), blocked.getCategory().getName());
    }
}