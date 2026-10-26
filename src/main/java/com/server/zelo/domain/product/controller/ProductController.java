package com.server.zelo.domain.product.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.api.PageResponse;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.dto.response.GetProductResponse;
import com.server.zelo.domain.product.dto.response.GetProductSummaryResponse;
import com.server.zelo.domain.product.service.ProductCommandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/products")
public class ProductController {

    private final ProductCommandService productCommandService;

    // 로그인 안 해도 조회 가능
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<GetProductSummaryResponse>>> searchProducts(
            @Valid @ModelAttribute SearchProductCondition condition) {
        return ResponseEntity.ok(ApiResponse.ok(productCommandService.searchProducts(condition)));
    }

    @GetMapping("/{productCode}")
    public ResponseEntity<ApiResponse<GetProductResponse>> getProduct(@PathVariable String productCode) {
        return ResponseEntity.ok(ApiResponse.ok(productCommandService.getProduct(productCode)));
    }
}