package com.server.zelo.domain.product.service;

import com.server.zelo.common.api.PageResponse;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.product.dto.request.AutocompleteCondition;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.dto.response.GetProductResponse;
import com.server.zelo.domain.product.dto.response.GetProductSummaryResponse;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.model.ProductDetail;
import com.server.zelo.domain.product.dto.response.GetPopularProductResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.List;
import java.util.Map;

// 재고는 재고 도메인 거라 상품이랑 같이 보여주려고 Command에서 묶음
@Service
@RequiredArgsConstructor
public class ProductCommandService {

    private static final int AUTOCOMPLETE_LIMIT = 10;
    private static final int POPULAR_LIMIT = 10;
    // 판매중지 상품을 빼고도 10개가 차게 후보를 넉넉히 가져옴
    private static final int POPULAR_CANDIDATES = 30;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final PopularKeywordService popularKeywordService;
    private final PopularProductService popularProductService;

    // 입력할 때마다 불려서 인기 검색어로는 안 셈
    public List<String> autocomplete(AutocompleteCondition condition) {
        if (condition.keyword().isEmpty()) {
            return List.of();
        }
        return productService.findProductNames(condition.keyword(), AUTOCOMPLETE_LIMIT);
    }

    // 최근 7일 많이 팔린 순으로 10개. 그사이 판매중지된 상품은 빼고 순위를 다시 매김
    @Transactional(readOnly = true)
    public List<GetPopularProductResponse> getPopularProducts() {
        Map<Long, Long> top = popularProductService.findTop(POPULAR_CANDIDATES);
        if (top.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> products = productService.findOnSaleProductsById(top.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, product -> product));
        List<Long> productIds = top.keySet().stream().filter(products::containsKey).limit(POPULAR_LIMIT).toList();
        Map<Long, Integer> stocks = inventoryService.findAvailableQuantities(productIds);
        return IntStream.range(0, productIds.size())
                .mapToObj(i -> {
                    Long productId = productIds.get(i);
                    return GetPopularProductResponse.of(i + 1, top.get(productId), products.get(productId),
                            stocks.getOrDefault(productId, 0));
                })
                .toList();
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