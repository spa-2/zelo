package com.server.zelo.domain.product.service;

import com.server.zelo.common.api.PageResponse;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.dto.response.GetProductResponse;
import com.server.zelo.domain.product.dto.response.GetProductSummaryResponse;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.model.ProductDetail;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 재고는 재고 도메인 거라 상품이랑 같이 보여주려고 Command에서 묶음
@Service
@RequiredArgsConstructor
public class ProductCommandService {

    private final ProductService productService;
    private final InventoryService inventoryService;

    @Transactional(readOnly = true)
    public PageResponse<GetProductSummaryResponse> searchProducts(SearchProductCondition condition) {
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