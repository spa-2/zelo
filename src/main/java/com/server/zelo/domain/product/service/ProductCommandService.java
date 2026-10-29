package com.server.zelo.domain.product.service;

import com.server.zelo.common.api.PageResponse;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.product.dto.request.AutocompleteCondition;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.dto.response.GetProductResponse;
import com.server.zelo.domain.product.dto.response.GetProductSummaryResponse;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.model.ProductDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

// 재고는 재고 도메인 거라 상품이랑 같이 보여주려고 Command에서 묶음
@Service
@RequiredArgsConstructor
public class ProductCommandService {

    private static final int AUTOCOMPLETE_LIMIT = 10;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final PopularKeywordService popularKeywordService;

    // 입력할 때마다 불려서 인기 검색어로는 안 셈
    public List<String> autocomplete(AutocompleteCondition condition) {
        if (condition.keyword().isEmpty()) {
            return List.of();
        }
        return productService.findProductNames(condition.keyword(), AUTOCOMPLETE_LIMIT);
    }

    @Transactional(readOnly = true)
    public PageResponse<GetProductSummaryResponse> searchProducts(SearchProductCondition condition) {
        // 다음 페이지로 넘길 때마다 세면 많이 넘겨 본 검색어가 올라가서 첫 페이지만 셈
        if (condition.page() == 0) {
            popularKeywordService.record(condition.keyword());
        }
        Page<Product> products = productService.searchProducts(condition);
        List<Long> productIds = products.getContent().stream().map(Product::getId).toList();
        Map<Long, Integer> stocks = inventoryService.findAvailableQuantities(productIds);
        return PageResponse.of(products,
                product -> GetProductSummaryResponse.of(product, stocks.getOrDefault(product.getId(), 0)));
    }

    @Transactional(readOnly = true)
    public GetProductResponse getProduct(String productCode) {
        ProductDetail detail = productService.getProductDetail(productCode);
        Integer stock = inventoryService.findAvailableQuantities(List.of(detail.productId())).getOrDefault(detail.productId(), 0);
        return GetProductResponse.of(detail, stock);
    }
}