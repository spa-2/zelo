package com.server.zelo.domain.user.service;

import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.service.CategoryService;
import com.server.zelo.domain.user.dto.response.CreateBlockedCategoryResponse;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserBlockedCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 카테고리는 상품 도메인 거라 있는지 확인하려고 Command에서 묶음
@Service
@RequiredArgsConstructor
public class UserBlockedCategoryCommandService {

    private final UserService userService;
    private final CategoryService categoryService;
    private final UserBlockedCategoryService userBlockedCategoryService;

    @Transactional
    public CreateBlockedCategoryResponse blockCategory(Long userId, Long categoryId) {
        User user = userService.getUser(userId);
        Category category = categoryService.getCategory(categoryId);
        UserBlockedCategory blocked = userBlockedCategoryService.blockCategory(user, category);
        return CreateBlockedCategoryResponse.from(blocked);
    }
}