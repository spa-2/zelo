package com.server.zelo.domain.quote.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.server.zelo.ai.llm.MenuPlan;
import com.server.zelo.ai.llm.MenuPlanIngredient;
import com.server.zelo.ai.llm.MenuPlanIntent;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.repository.QuoteRepository;
import com.server.zelo.domain.review.service.ReviewService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class QuoteCommandServiceTest {

    @Mock
    private ReviewService reviewService;

    @Mock
    private ProductService productService;

    @Mock
    private QuoteRepository quoteRepository;

    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private QuoteCommandService quoteCommandService;

    private final Ingredient kimchi = Ingredient.of(Category.of(Category.of("국·반찬", true), "김치·젓갈"), "김치", BaseUnit.G);
    private final Ingredient soju = Ingredient.of(Category.of(Category.of("주류", false), "소주·전통주"), "소주", BaseUnit.ML);

    @Test
    void 조건_키워드가_붙은_상품을_고르고_필요량만큼_담는다() {
        // given
        Product pogi = product(1L, kimchi, "포기김치 500g", 5900L, 500, 120);
        Product baek = product(2L, kimchi, "백김치 1kg", 8900L, 1000, 80);
        givenProducts(List.of(pogi, baek), Map.of(1L, 100, 2L, 100), Map.of(2L, Set.of(Keyword.NOT_SPICY)));

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L,
                menuPlan(List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of(Keyword.NOT_SPICY)));

        // then
        assertThat(quote.items()).extracting(QuoteItem::productName, QuoteItem::quantity)
                .containsExactly(tuple("백김치 1kg", 1));
        assertThat(quote.totalAmount()).isEqualTo(8900L);
        then(quoteRepository).should().save(quote);
    }

    @Test
    void 조건이_없으면_필요량_총액이_싼_상품을_고른다() {
        // given
        Product small = product(1L, kimchi, "포기김치 500g", 5900L, 500, 100);
        Product big = product(2L, kimchi, "포기김치 1kg", 10900L, 1000, 100);
        givenProducts(List.of(small, big), Map.of(1L, 100, 2L, 100), Map.of());

        // when (1kg 필요 -> 500g 2개 11,800원 vs 1kg 1개 10,900원)
        Quote quote = quoteCommandService.createQuote(1L, 5L,
                menuPlan(List.of(MenuPlanIngredient.of("김치", 1000, BaseUnit.G)), List.of()));

        // then
        assertThat(quote.items()).extracting(QuoteItem::productName).containsExactly("포기김치 1kg");
    }

    @Test
    void 없는_재료와_주류와_품절은_제외_목록에_넣는다() {
        // given
        Product sojuProduct = product(1L, soju, "소주 360ml", 1900L, 360, 100);
        Product pogi = product(2L, kimchi, "포기김치 500g", 5900L, 500, 100);
        givenProducts(List.of(sojuProduct, pogi), Map.of(1L, 100, 2L, 0), Map.of());

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L, menuPlan(List.of(
                MenuPlanIngredient.of("김가루", 10, BaseUnit.G),
                MenuPlanIngredient.of("소주", 360, BaseUnit.ML),
                MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of()));

        // then
        assertThat(quote.items()).isEmpty();
        assertThat(quote.excludedItems()).extracting(ExcludedItem::name, ExcludedItem::reason).containsExactly(
                tuple("김가루", "PRODUCT_NOT_FOUND"),
                tuple("소주", "CATEGORY_NOT_ALLOWED"),
                tuple("김치", "OUT_OF_STOCK"));
    }

    private void givenProducts(List<Product> products, Map<Long, Integer> stocks, Map<Long, Set<Keyword>> keywords) {
        given(productService.findOnSaleProducts(anyList())).willReturn(products);
        given(inventoryService.findAvailableQuantities(anyCollection())).willReturn(stocks);
        given(productService.findKeywords(anyCollection())).willReturn(keywords);
        given(reviewService.findReviewEvidences(anyList(), anySet())).willReturn(Map.of());
    }

    private MenuPlan menuPlan(List<MenuPlanIngredient> ingredients, List<Keyword> conditions) {
        return MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 4, null, ingredients, conditions, List.of());
    }

    private Product product(Long id, Ingredient ingredient, String name, Long price, int contentAmount, int salesCount) {
        Product product = Product.of("P-" + id, ingredient, "브랜드 A", name, null, price, contentAmount, "1개");
        ReflectionTestUtils.setField(product, "id", id);
        ReflectionTestUtils.setField(product, "salesCount", salesCount);
        return product;
    }
}