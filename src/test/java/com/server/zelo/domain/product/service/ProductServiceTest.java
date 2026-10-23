package com.server.zelo.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductKeyword;
import com.server.zelo.domain.product.entity.ProductStatus;
import com.server.zelo.domain.product.repository.IngredientRepository;
import com.server.zelo.domain.product.repository.ProductKeywordRepository;
import com.server.zelo.domain.product.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

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

    private Product product(Long id) {
        Product product = Product.of("P-" + id, kimchi, "브랜드 A", "백김치 1kg", null, 8900L, 1000, "1kg");
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}