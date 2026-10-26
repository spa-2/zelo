package com.server.zelo.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import com.server.zelo.common.api.PageResponse;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.dto.response.GetProductResponse;
import com.server.zelo.domain.product.dto.response.GetProductSummaryResponse;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductCommandServiceTest {

    @Mock
    private ProductService productService;

    @Mock
    private InventoryService inventoryService;

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
    void 상세에_키워드와_재고를_같이_준다() {
        // given
        Product product = product(1L, "백김치 1kg");
        given(productService.getProduct("P-1")).willReturn(product);
        given(productService.findKeywords(List.of(1L))).willReturn(Map.of(1L, Set.of(Keyword.NOT_SPICY, Keyword.DOMESTIC)));
        given(inventoryService.findAvailableQuantities(List.of(1L))).willReturn(Map.of(1L, 3));

        // when
        GetProductResponse result = productCommandService.getProduct("P-1");

        // then
        assertThat(result.keywords()).containsExactly(Keyword.NOT_SPICY, Keyword.DOMESTIC);
        assertThat(result.availableQuantity()).isEqualTo(3);
        assertThat(result.categoryName()).isEqualTo("김치·젓갈");
    }

    private Product product(Long id, String name) {
        Product product = Product.of("P-" + id, kimchi, "브랜드 A", name, null, 8900L, 1000, "1kg");
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}