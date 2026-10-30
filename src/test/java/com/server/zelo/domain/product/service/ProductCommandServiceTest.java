package com.server.zelo.domain.product.service;

import com.server.zelo.common.api.PageResponse;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.product.dto.request.AutocompleteCondition;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.dto.response.GetProductResponse;
import com.server.zelo.domain.product.dto.response.GetProductSummaryResponse;
import com.server.zelo.domain.product.entity.*;
import com.server.zelo.domain.product.model.ProductDetail;
import com.server.zelo.domain.product.dto.response.GetPopularProductResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class ProductCommandServiceTest {

    @Mock
    private ProductService productService;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private PopularKeywordService popularKeywordService;

    @Mock
    private PopularProductService popularProductService;

    @InjectMocks
    private ProductCommandService productCommandService;

    private final Ingredient kimchi = Ingredient.of(Category.of(Category.of("국·반찬", true), "김치·젓갈"), "김치", BaseUnit.G);

    @Test
    void 검색_결과에_재고를_붙이고_재고_정보가_없으면_0으로_준다() {
        // given
        SearchProductCondition condition = new SearchProductCondition(null, null, null, null, null, null);
        given(productService.searchProducts(condition))
                .willReturn(new PageImpl<>(List.of(product(1L, "백김치 1kg"), product(2L, "포기김치 500g"))));
        given(inventoryService.findAvailableQuantities(List.of(1L, 2L))).willReturn(Map.of(1L, 7));

        // when
        PageResponse<GetProductSummaryResponse> result = productCommandService.searchProducts(condition);

        // then
        assertThat(result.content())
                .extracting(GetProductSummaryResponse::name, GetProductSummaryResponse::availableQuantity)
                .containsExactly(tuple("백김치 1kg", 7), tuple("포기김치 500g", 0));
    }

    @Test
    void 첫_페이지_검색만_인기_검색어로_센다() {
        // given
        SearchProductCondition first = new SearchProductCondition("김치", null, null, null, 0, null);
        SearchProductCondition next = new SearchProductCondition("김치", null, null, null, 1, null);
        given(productService.searchProducts(any())).willReturn(new PageImpl<>(List.of()));

        // when
        productCommandService.searchProducts(first);
        productCommandService.searchProducts(next);

        // then
        then(popularKeywordService).should(times(1)).record("김치");
    }

    @Test
    void 상세에_키워드와_재고를_같이_준다() {
        // given
        ProductDetail detail = ProductDetail.from(product(1L, "백김치 1kg"), Set.of(Keyword.NOT_SPICY, Keyword.DOMESTIC));
        given(productService.getProductDetail("P-1")).willReturn(detail);
        given(inventoryService.findAvailableQuantities(List.of(1L))).willReturn(Map.of(1L, 3));
        // when
        GetProductResponse result = productCommandService.getProduct("P-1");

        // then
        assertThat(result.keywords()).containsExactly(Keyword.NOT_SPICY, Keyword.DOMESTIC);
        assertThat(result.availableQuantity()).isEqualTo(3);
        assertThat(result.categoryName()).isEqualTo("김치·젓갈");
    }
    @Test
    void 자동완성은_검색어가_비면_상품을_안_찾는다() {
        // when
        List<String> result = productCommandService.autocomplete(new AutocompleteCondition("  "));

        // then
        assertThat(result).isEmpty();
        then(productService).shouldHaveNoInteractions();
    }

    @Test
    void 자동완성은_인기_검색어로_안_센다() {
        // given
        given(productService.findProductNames("김치", 10)).willReturn(List.of("백김치 1kg"));

        // when
        List<String> result = productCommandService.autocomplete(new AutocompleteCondition(" 김치 "));

        // then
        assertThat(result).containsExactly("백김치 1kg");
        then(popularKeywordService).shouldHaveNoInteractions();
    }

    @Test
    void 인기_상품은_판매중지된_상품을_빼고_순위를_다시_매긴다() {
        // given
        Map<Long, Long> top = new LinkedHashMap<>();
        top.put(3L, 9L);
        top.put(1L, 5L);
        top.put(2L, 4L);
        given(popularProductService.findTop(30)).willReturn(top);
        given(productService.findOnSaleProductsById(top.keySet()))
                .willReturn(List.of(product(1L, "백김치 1kg"), product(2L, "포기김치 500g")));
        given(inventoryService.findAvailableQuantities(List.of(1L, 2L))).willReturn(Map.of(1L, 7));

        // when
        List<GetPopularProductResponse> result = productCommandService.getPopularProducts();

        // then
        assertThat(result)
                .extracting(GetPopularProductResponse::rank, GetPopularProductResponse::recentSalesCount,
                        response -> response.product().name(), response -> response.product().availableQuantity())
                .containsExactly(tuple(1, 5L, "백김치 1kg", 7), tuple(2, 4L, "포기김치 500g", 0));
    }

    @Test
    void 인기_상품은_10개까지만_준다() {
        // given
        Map<Long, Long> top = new LinkedHashMap<>();
        List<Product> products = new ArrayList<>();
        for (long id = 1; id <= 12; id++) {
            top.put(id, 20 - id);
            products.add(product(id, "상품 " + id));
        }
        given(popularProductService.findTop(30)).willReturn(top);
        given(productService.findOnSaleProductsById(top.keySet())).willReturn(products);

        // when
        List<GetPopularProductResponse> result = productCommandService.getPopularProducts();

        // then
        assertThat(result).hasSize(10);
        assertThat(result.get(9).rank()).isEqualTo(10);
    }

    @Test
    void 팔린_상품이_없으면_상품을_안_찾는다() {
        // given
        given(popularProductService.findTop(30)).willReturn(Map.of());

        // when
        List<GetPopularProductResponse> result = productCommandService.getPopularProducts();

        // then
        assertThat(result).isEmpty();
        then(productService).shouldHaveNoInteractions();
    }

    private Product product(Long id, String name) {
        Product product = Product.of("P-" + id, kimchi, "브랜드 A", name, null, 8900L, 1000, "1kg");
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}