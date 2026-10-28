package com.server.zelo.domain.product.model;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductStatus;
import java.util.List;
import java.util.Set;

// 상품 상세에서 캐시하는 부분. 재고는 주문마다 바뀌어서 빼고 매번 따로 조회함
public record ProductDetail(
        Long productId, // 재고 조회용
        String productCode,
        String brand,
        String name,
        String description,
        Long price,
        String unitLabel,
        String categoryName,
        List<Keyword> keywords,
        ProductStatus status
) {

    public static ProductDetail from(Product product, Set<Keyword> keywords) {
        return new ProductDetail(product.getId(), product.getProductCode(), product.getBrand(), product.getName(),
                product.getDescription(), product.getPrice(), product.getUnitLabel(), product.getCategory().getName(),
                keywords.stream().sorted().toList(), product.getStatus());
    }
}