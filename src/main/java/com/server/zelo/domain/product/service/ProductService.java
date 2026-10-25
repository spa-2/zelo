package com.server.zelo.domain.product.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductKeyword;
import com.server.zelo.domain.product.entity.ProductStatus;
import com.server.zelo.domain.product.repository.IngredientRepository;
import com.server.zelo.domain.product.repository.ProductKeywordRepository;
import com.server.zelo.domain.product.repository.ProductRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final IngredientRepository ingredientRepository;
    private final ProductKeywordRepository productKeywordRepository;

    // AI 메뉴 계획이 재료 이름·단위를 이 목록에서만 고르게 한다. 견적이 재료 이름으로 상품을 찾고 단위로 수량을 계산하기 때문.
    // 프롬프트에 매번 같은 순서로 들어가도록 id 순서를 지킨다
    @Transactional(readOnly = true)
    public Map<String, BaseUnit> findIngredientUnits() {
        return ingredientRepository.findAll(Sort.by("id")).stream()
                .collect(Collectors.toMap(Ingredient::getName, Ingredient::getBaseUnit,
                        (first, second) -> first, LinkedHashMap::new));
    }

    @Transactional(readOnly = true)
    public Page<Product> searchProducts(SearchProductCondition condition) {
        return productRepository.search(condition.keyword(), condition.categoryId(), condition.inStock(),
                ProductStatus.ON_SALE, PageRequest.of(condition.page(), condition.size(), condition.sort().toSort()));
    }

    @Transactional(readOnly = true)
    public Product getProduct(String productCode) {
        return productRepository.findByProductCode(productCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

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

    // 결제 완료 때 주문 쪽에서 부름
    @Transactional
    public void increaseSalesCount(Long productId, Integer quantity) {
        productRepository.increaseSalesCount(productId, quantity);
    }

    // 취소·환불 때 주문 쪽에서 부름
    @Transactional
    public void decreaseSalesCount(Long productId, Integer quantity) {
        productRepository.decreaseSalesCount(productId, quantity);
    }
}