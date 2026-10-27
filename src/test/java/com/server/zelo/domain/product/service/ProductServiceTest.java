package com.server.zelo.domain.product.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.dto.request.CreateProductRequest;
import com.server.zelo.domain.product.dto.request.ProductSort;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.dto.request.UpdateProductRequest;
import com.server.zelo.domain.product.entity.*;
import com.server.zelo.domain.product.repository.IngredientRepository;
import com.server.zelo.domain.product.repository.ProductKeywordRepository;
import com.server.zelo.domain.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private IngredientRepository ingredientRepository;

    @Mock
    private ProductKeywordRepository productKeywordRepository;

    @InjectMocks
    private ProductService productService;

    private final Ingredient kimchi = Ingredient.of(Category.of(Category.of("국·반찬", true), "김치·젓갈"), "김치", BaseUnit.G);

    @Test
    void 재료_이름과_기본_단위를_id_순서대로_돌려준다() {
        // given
        Ingredient egg = Ingredient.of(Category.of(Category.of("정육·계란", true), "계란"), "계란", BaseUnit.EA);
        given(ingredientRepository.findAll(Sort.by("id"))).willReturn(List.of(kimchi, egg));

        // when
        Map<String, BaseUnit> result = productService.findIngredientUnits();

        // then: 프롬프트에 매번 같은 순서로 들어가야 한다
        assertThat(result).containsExactly(Map.entry("김치", BaseUnit.G), Map.entry("계란", BaseUnit.EA));
    }

    @Test
    void 재료_이름으로_판매중인_상품을_찾는다() {
        // given
        Product product = product(1L);
        given(productRepository.findAllByIngredient_NameInAndStatus(List.of("김치"), ProductStatus.ON_SALE))
                .willReturn(List.of(product));

        // when
        List<Product> result = productService.findOnSaleProducts(List.of("김치"));

        // then
        assertThat(result).containsExactly(product);
    }

    @Test
    void 상품별로_키워드를_묶는다() {
        // given
        Product product = product(1L);
        given(productKeywordRepository.findAllByProduct_IdIn(List.of(1L))).willReturn(List.of(
                ProductKeyword.of(product, Keyword.NOT_SPICY), ProductKeyword.of(product, Keyword.DOMESTIC)));

        // when
        Map<Long, Set<Keyword>> result = productService.findKeywords(List.of(1L));

        // then
        assertThat(result.get(1L)).containsExactlyInAnyOrder(Keyword.NOT_SPICY, Keyword.DOMESTIC);
    }

    @Test
    void 상품_ID_목록으로_상품을_찾는다() {
        // given
        Product product = product(1L);
        given(productRepository.findAllById(List.of(1L))).willReturn(List.of(product));

        // when
        List<Product> result = productService.findProducts(List.of(1L));

        // then
        assertThat(result).containsExactly(product);
    }

    @Test
    void 검색_조건을_판매중_상품_조회로_넘긴다() {
        // given
        Product product = product(1L);
        SearchProductCondition condition = new SearchProductCondition("김치", 3L, true, ProductSort.PRICE_ASC, 1, 10);
        given(productRepository.search("김치", 3L, true, ProductStatus.ON_SALE,
                PageRequest.of(1, 10, ProductSort.PRICE_ASC.toSort()))).willReturn(new PageImpl<>(List.of(product)));

        // when
        Page<Product> result = productService.searchProducts(condition);

        // then
        assertThat(result.getContent()).containsExactly(product);
    }

    @Test
    void 상품_코드로_상품을_찾는다() {
        // given
        Product product = product(1L);
        given(productRepository.findByProductCode("P-1")).willReturn(Optional.of(product));

        // when
        Product result = productService.getProduct("P-1");

        // then
        assertThat(result).isEqualTo(product);
    }

    @Test
    void 없는_상품_코드면_예외가_발생한다() {
        // given
        given(productRepository.findByProductCode("P-99")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.getProduct("P-99"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    void 판매량을_올린다() {
        // when
        productService.increaseSalesCount(1L, 3);

        // then
        then(productRepository).should().increaseSalesCount(1L, 3);
    }

    @Test
    void 판매량을_내린다() {
        // when
        productService.decreaseSalesCount(1L, 3);

        // then
        then(productRepository).should().decreaseSalesCount(1L, 3);
    }

    @Test
    void 상품을_등록하면_재료의_카테고리로_판매중_상품을_만든다() {
        // given
        given(productRepository.existsByProductCode("P-NEW")).willReturn(false);
        given(ingredientRepository.findById(3L)).willReturn(Optional.of(kimchi));
        given(productRepository.saveAndFlush(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Product product = productService.createProduct(createRequest());

        // then
        assertThat(product.getProductCode()).isEqualTo("P-NEW");
        assertThat(product.getCategory().getName()).isEqualTo("김치·젓갈");
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    void 이미_있는_상품_코드면_예외가_발생한다() {
        // given
        given(productRepository.existsByProductCode("P-NEW")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> productService.createProduct(createRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_PRODUCT_CODE);
    }

    @Test
    void 확인한_사이에_같은_코드가_먼저_저장되면_중복_예외로_바꾼다() {
        // given
        given(productRepository.existsByProductCode("P-NEW")).willReturn(false);
        given(ingredientRepository.findById(3L)).willReturn(Optional.of(kimchi));
        given(productRepository.saveAndFlush(any(Product.class))).willThrow(new DataIntegrityViolationException("duplicate"));

        // when & then
        assertThatThrownBy(() -> productService.createProduct(createRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_PRODUCT_CODE);
    }

    @Test
    void 없는_재료로_등록하면_예외가_발생한다() {
        // given
        given(productRepository.existsByProductCode("P-NEW")).willReturn(false);
        given(ingredientRepository.findById(3L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.createProduct(createRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INGREDIENT_NOT_FOUND);
    }

    @Test
    void 상품_가격과_설명을_바꾼다() {
        // given
        given(productRepository.findByProductCode("P-1")).willReturn(Optional.of(product(1L)));

        // when
        Product product = productService.updateProduct("P-1", new UpdateProductRequest(9900L, "새 설명"));

        // then
        assertThat(product.getPrice()).isEqualTo(9900L);
        assertThat(product.getDescription()).isEqualTo("새 설명");
    }

    @Test
    void 상품을_판매중지하고_다시_판매한다() {
        // given
        given(productRepository.findByProductCode("P-1")).willReturn(Optional.of(product(1L)));

        // when
        ProductStatus stopped = productService.stopSale("P-1").getStatus();
        ProductStatus resumed = productService.resumeSale("P-1").getStatus();

        // then
        assertThat(stopped).isEqualTo(ProductStatus.STOP_SALE);
        assertThat(resumed).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    void 없는_상품을_바꾸면_예외가_발생한다() {
        // given
        given(productRepository.findByProductCode("P-404")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.stopSale("P-404"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    private CreateProductRequest createRequest() {
        return new CreateProductRequest("P-NEW", 3L, "브랜드 A", "백김치 1kg", null, 8900L, 1000, "1kg");
    }

    private Product product(Long id) {
        Product product = Product.of("P-" + id, kimchi, "브랜드 A", "백김치 1kg", null, 8900L, 1000, "1kg");
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}