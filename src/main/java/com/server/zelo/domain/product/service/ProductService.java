package com.server.zelo.domain.product.service;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductKeyword;
import com.server.zelo.domain.product.entity.ProductStatus;
import com.server.zelo.domain.product.repository.ProductKeywordRepository;
import com.server.zelo.domain.product.repository.ProductRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductKeywordRepository productKeywordRepository;

    @Transactional(readOnly = true)
    public List<Product> findOnSaleProducts(Collection<String> ingredientNames) {
        return productRepository.findAllByIngredient_NameInAndStatus(ingredientNames, ProductStatus.ON_SALE);
    }

    // 주문에서 상품 연결이랑 가격 비교할 때 씀
    @Transactional(readOnly = true)
    public List<Product> findProducts(Collection<Long> productIds) {
        return productRepository.findAllById(productIds);
    }

    @Transactional(readOnly = true)
    public Map<Long, Set<Keyword>> findKeywords(Collection<Long> productIds) {
        return productKeywordRepository.findAllByProduct_IdIn(productIds).stream()
                .collect(Collectors.groupingBy(productKeyword -> productKeyword.getProduct().getId(),
                        Collectors.mapping(ProductKeyword::getKeyword, Collectors.toSet())));
    }
}