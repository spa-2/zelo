package com.server.zelo.domain.product.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.api.PageResponse;
import com.server.zelo.domain.product.dto.request.AutocompleteCondition;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.dto.response.GetPopularKeywordResponse;
import com.server.zelo.domain.product.dto.response.GetProductResponse;
import com.server.zelo.domain.product.dto.response.GetProductSummaryResponse;
import com.server.zelo.domain.product.service.PopularKeywordService;
import com.server.zelo.domain.product.service.ProductCommandService;
import com.server.zelo.domain.product.dto.response.GetPopularProductResponse;
import jakarta.validation.Valid;
import java.util.List;
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

    private final PopularKeywordService popularKeywordService;
    private final ProductCommandService productCommandService;

    // 로그인 안 해도 조회 가능
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<GetProductSummaryResponse>>> searchProducts(
            @Valid @ModelAttribute SearchProductCondition condition) {
        return ResponseEntity.ok(ApiResponse.ok(productCommandService.searchProducts(condition)));
    }

    // 최근 24시간 많이 검색한 순으로 10개
    @GetMapping("/popular-keywords")
    public ResponseEntity<ApiResponse<List<GetPopularKeywordResponse>>> getPopularKeywords() {
        return ResponseEntity.ok(ApiResponse.ok(popularKeywordService.getPopularKeywords()));
    }

    // 입력 중인 글자가 들어간 상품 이름 10개
    @GetMapping("/autocomplete")
    public ResponseEntity<ApiResponse<List<String>>> autocomplete(@Valid @ModelAttribute AutocompleteCondition condition) {
        return ResponseEntity.ok(ApiResponse.ok(productCommandService.autocomplete(condition)));
    }

    // 최근 7일 많이 팔린 순으로 10개
    @GetMapping("/popular")
    public ResponseEntity<ApiResponse<List<GetPopularProductResponse>>> getPopularProducts() {
        return ResponseEntity.ok(ApiResponse.ok(productCommandService.getPopularProducts()));
    }

    @GetMapping("/{productCode}")
    public ResponseEntity<ApiResponse<GetProductResponse>> getProduct(@PathVariable String productCode) {
        return ResponseEntity.ok(ApiResponse.ok(productCommandService.getProduct(productCode)));
    }
}