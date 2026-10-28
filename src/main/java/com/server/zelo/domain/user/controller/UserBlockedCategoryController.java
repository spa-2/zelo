package com.server.zelo.domain.user.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.user.dto.request.CreateBlockedCategoryRequest;
import com.server.zelo.domain.user.dto.response.CreateBlockedCategoryResponse;
import com.server.zelo.domain.user.dto.response.GetBlockedCategoryResponse;
import com.server.zelo.domain.user.service.UserBlockedCategoryCommandService;
import com.server.zelo.domain.user.service.UserBlockedCategoryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 견적에서 빼 달라고 막은 카테고리 (알레르기 등)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users/me/blocked-categories")
public class UserBlockedCategoryController {

    private final UserBlockedCategoryService userBlockedCategoryService;
    private final UserBlockedCategoryCommandService userBlockedCategoryCommandService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<GetBlockedCategoryResponse>>> getBlockedCategories(@Auth AuthUser authUser) {
        List<GetBlockedCategoryResponse> response = userBlockedCategoryService.findBlockedCategories(authUser.id()).stream()
                .map(GetBlockedCategoryResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateBlockedCategoryResponse>> blockCategory(
            @Auth AuthUser authUser, @Valid @RequestBody CreateBlockedCategoryRequest request) {
        CreateBlockedCategoryResponse response = userBlockedCategoryCommandService.blockCategory(authUser.id(), request.categoryId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> unblockCategory(@Auth AuthUser authUser, @PathVariable Long categoryId) {
        userBlockedCategoryService.unblockCategory(authUser.id(), categoryId);
        return ResponseEntity.noContent().build();
    }
}