package com.server.zelo.domain.product.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.dto.request.CreateProductRequest;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.dto.request.UpdateProductRequest;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductKeyword;
import com.server.zelo.domain.product.entity.ProductStatus;
import com.server.zelo.domain.product.model.ProductDetail;
import com.server.zelo.domain.product.repository.IngredientRepository;
import com.server.zelo.domain.product.repository.ProductKeywordRepository;
import com.server.zelo.domain.product.repository.ProductRepository;
import com.server.zelo.global.config.CacheConfig;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
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
    // 채팅 메시지마다 부르는데 재료는 거의 안 바뀌어서 캐시함
    @Cacheable(cacheNames = CacheConfig.INGREDIENT_UNITS)
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

    // 상품 상세에서 재고만 빼고 캐시함. 관리자 API로 바꾸면 아래 @CacheEvict로 지움
    @Cacheable(cacheNames = CacheConfig.PRODUCT_DETAIL, key = "#productCode")
    @Transactional(readOnly = true)
    public ProductDetail getProductDetail(String productCode) {
        Product product = getProduct(productCode);
        Set<Keyword> keywords = findKeywords(List.of(product.getId())).getOrDefault(product.getId(), Set.of());
        return ProductDetail.from(product, keywords);
    }

    @Transactional(readOnly = true)
    public List<Product> findOnSaleProducts(Collection<String> ingredientNames) {
        return productRepository.findAllByIngredient_NameInAndStatus(ingredientNames, ProductStatus.ON_SALE);
    }

    @Transactional
    public Product createProduct(CreateProductRequest request) {
        if (productRepository.existsByProductCode(request.productCode())) {
            throw new BusinessException(ErrorCode.DUPLICATE_PRODUCT_CODE);
        }
        Ingredient ingredient = ingredientRepository.findWithCategoryById(request.ingredientId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INGREDIENT_NOT_FOUND));
        Product product = Product.of(request.productCode(), ingredient, request.brand(), request.name(),
                request.description(), request.price(), request.contentAmount(), request.unitLabel());
        // 위에서 확인한 사이에 같은 코드로 먼저 들어온 경우는 유니크 제약에서 걸림
        try {
            return productRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            // products의 유니크 제약은 product_code 하나라 유니크 위반이면 코드 중복
            if (isUniqueViolation(e)) {
                throw new BusinessException(ErrorCode.DUPLICATE_PRODUCT_CODE);
            }
            throw e;
        }
    }

    @CacheEvict(cacheNames = CacheConfig.PRODUCT_DETAIL, key = "#productCode")
    @Transactional
    public Product updateProduct(String productCode, UpdateProductRequest request) {
        Product product = getProductForUpdate(productCode);
        product.update(request.price(), request.description());
        return product;
    }

    @CacheEvict(cacheNames = CacheConfig.PRODUCT_DETAIL, key = "#productCode")
    @Transactional
    public Product stopSale(String productCode) {
        Product product = getProductForUpdate(productCode);
        product.stopSale();
        return product;
    }

    @CacheEvict(cacheNames = CacheConfig.PRODUCT_DETAIL, key = "#productCode")
    @Transactional
    public Product resumeSale(String productCode) {
        Product product = getProductForUpdate(productCode);
        product.resumeSale();
        return product;
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

    // PostgreSQL 유니크 위반 코드로 판별
    private boolean isUniqueViolation(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private Product getProductForUpdate(String productCode) {
        return productRepository.findByProductCode(productCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}