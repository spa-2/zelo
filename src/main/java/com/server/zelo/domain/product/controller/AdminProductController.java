package com.server.zelo.domain.product.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.domain.product.dto.request.CreateProductRequest;
import com.server.zelo.domain.product.dto.request.UpdateProductRequest;
import com.server.zelo.domain.product.dto.response.CreateProductResponse;
import com.server.zelo.domain.product.dto.response.UpdateProductResponse;
import com.server.zelo.domain.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// ADMIN만 호출할 수 있게 SecurityConfig에서 /api/admin/** 로 막음
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductService productService;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateProductResponse>> createProduct(
            @Valid @RequestBody CreateProductRequest request) {
        CreateProductResponse response = CreateProductResponse.from(productService.createProduct(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PatchMapping("/{productCode}")
    public ResponseEntity<ApiResponse<UpdateProductResponse>> updateProduct(
            @PathVariable String productCode, @Valid @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(UpdateProductResponse.from(productService.updateProduct(productCode, request))));
    }

    @PatchMapping("/{productCode}/stop")
    public ResponseEntity<ApiResponse<UpdateProductResponse>> stopSale(@PathVariable String productCode) {
        return ResponseEntity.ok(ApiResponse.ok(UpdateProductResponse.from(productService.stopSale(productCode))));
    }

    @PatchMapping("/{productCode}/resume")
    public ResponseEntity<ApiResponse<UpdateProductResponse>> resumeSale(@PathVariable String productCode) {
        return ResponseEntity.ok(ApiResponse.ok(UpdateProductResponse.from(productService.resumeSale(productCode))));
    }
}