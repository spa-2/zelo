package com.server.zelo.domain.product.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.domain.product.dto.response.GetCategoryResponse;
import com.server.zelo.domain.product.service.CategoryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    // 로그인 안 해도 조회 가능
    @GetMapping
    public ResponseEntity<ApiResponse<List<GetCategoryResponse>>> getCategories() {
        List<GetCategoryResponse> response = categoryService.getCategories();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}