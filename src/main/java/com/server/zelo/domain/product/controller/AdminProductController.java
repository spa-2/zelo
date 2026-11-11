package com.server.zelo.domain.product.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.domain.product.dto.request.AddStockRequest;
import com.server.zelo.domain.product.dto.request.CreateProductRequest;
import com.server.zelo.domain.product.dto.request.UpdateProductRequest;
import com.server.zelo.domain.product.dto.response.AddStockResponse;
import com.server.zelo.domain.product.dto.response.CreateProductResponse;
import com.server.zelo.domain.product.dto.response.UpdateProductResponse;
import com.server.zelo.domain.product.service.ProductCommandService;
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

// ADMIN 확인은 회원 role 작업에서 SecurityConfig로 /api/admin/** 를 막을 예정. 그 전까지는 다른 API처럼 열려 있음
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductService productService;
    private final ProductCommandService productCommandService;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateProductResponse>> createProduct(
            @Valid @RequestBody CreateProductRequest request) {
        CreateProductResponse response = productCommandService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    // 입고. 보낸 수량만큼 재고를 늘림 (덮어쓰지 않음)
    @PostMapping("/{productCode}/stock")
    public ResponseEntity<ApiResponse<AddStockResponse>> addStock(
            @PathVariable String productCode, @Valid @RequestBody AddStockRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(productCommandService.addStock(productCode, request.quantity())));
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