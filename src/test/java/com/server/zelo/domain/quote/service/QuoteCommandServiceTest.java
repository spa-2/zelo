package com.server.zelo.domain.quote.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
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
import com.server.zelo.domain.quote.model.QuoteItemReason;
import com.server.zelo.domain.quote.repository.QuoteRepository;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import com.server.zelo.domain.review.service.ReviewService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Test
    void 고른_상품에_판매량_리뷰_조건_일치_여부를_근거로_담는다() {
        // given
        Product pogi = product(1L, kimchi, "포기김치 500g", 5900L, 500, 120);
        Product baek = product(2L, kimchi, "백김치 1kg", 8900L, 1000, 80);
        given(productService.findOnSaleProducts(anyList())).willReturn(List.of(pogi, baek));
        given(inventoryService.findAvailableQuantities(anyCollection())).willReturn(Map.of(1L, 100, 2L, 100));
        given(productService.findKeywords(anyCollection())).willReturn(Map.of(2L, Set.of(Keyword.NOT_SPICY)));
        given(reviewService.findReviewEvidences(anyList(), anySet())).willReturn(Map.of(2L, ProductReviewEvidence.of(
                2L, new BigDecimal("4.6"), 23, Map.of(Keyword.NOT_SPICY, 15), null)));

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L,
                menuPlan(List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of(Keyword.NOT_SPICY)));

        // then
        QuoteItemReason reason = quote.items().get(0).reason();
        assertThat(reason.salesCount()).isEqualTo(80);
        assertThat(reason.avgRating()).isEqualByComparingTo("4.6");
        assertThat(reason.reviewCount()).isEqualTo(23);
        assertThat(reason.keywordMentions()).containsEntry(Keyword.NOT_SPICY, 15);
        assertThat(reason.conditionMatched()).isTrue();
    }

    @Test
    void 조건에_맞는_상품이_없으면_조건_불일치로_담고_리뷰가_없으면_비워둔다() {
        // given
        givenProducts(List.of(product(1L, kimchi, "포기김치 500g", 5900L, 500, 120)), Map.of(1L, 100), Map.of());

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L,
                menuPlan(List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of(Keyword.NOT_SPICY)));

        // then
        QuoteItemReason reason = quote.items().get(0).reason();
        assertThat(reason.conditionMatched()).isFalse();
        assertThat(reason.avgRating()).isNull();
        assertThat(reason.reviewCount()).isZero();
        assertThat(reason.keywordMentions()).isEmpty();
    }

    @Test
    void 수량을_바꿔도_근거는_그대로_둔다() {
        // given
        QuoteItemReason reason = new QuoteItemReason(80, new BigDecimal("4.6"), 23, Map.of(), true);
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.of(new Quote("q-1", 1L, 5L, "김치볶음밥",
                List.of(new QuoteItem(1L, "P-1", "백김치 1kg", "1kg", 8900L, 1, 8900L, reason)), List.of(), 8900L,
                LocalDateTime.now(), LocalDateTime.now().plusMinutes(10))));
        given(inventoryService.findAvailableQuantities(List.of(1L))).willReturn(Map.of(1L, 10));

        // when
        Quote quote = quoteCommandService.changeQuantity(1L, 5L, 1L, 2);

        // then
        assertThat(quote.items().get(0).reason()).isEqualTo(reason);
    }

    @Test
    void 상품을_빼면_새_견적_ID로_저장한다() {
        // given
        givenQuote(List.of(item(1L, 5900L, 1), item(2L, 3000L, 2)), List.of());

        // when
        Quote quote = quoteCommandService.removeItem(1L, 5L, 1L);

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(2L);
        assertThat(quote.totalAmount()).isEqualTo(6000L);
        assertThat(quote.quoteId()).isNotEqualTo("q-1");
        then(quoteRepository).should().save(quote);
    }

    @Test
    void 견적에_없는_상품을_빼면_예외가_발생한다() {
        // given
        givenQuote(List.of(item(1L, 5900L, 1)), List.of());

        // when & then
        assertThatThrownBy(() -> quoteCommandService.removeItem(1L, 5L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    void 다른_사람_견적은_고칠_수_없다() {
        // given
        givenQuote(List.of(item(1L, 5900L, 1)), List.of());

        // when & then
        assertThatThrownBy(() -> quoteCommandService.removeItem(2L, 5L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NO_AUTHORITY);
    }

    @Test
    void 견적이_없으면_만료_예외가_발생한다() {
        // given
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> quoteCommandService.removeItem(1L, 5L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.QUOTE_EXPIRED);
    }

    @Test
    void 수량을_바꾸면_금액도_다시_계산한다() {
        // given
        givenQuote(List.of(item(1L, 5900L, 1)), List.of());
        given(inventoryService.findAvailableQuantities(List.of(1L))).willReturn(Map.of(1L, 10));

        // when
        Quote quote = quoteCommandService.changeQuantity(1L, 5L, 1L, 3);

        // then
        assertThat(quote.items()).extracting(QuoteItem::quantity, QuoteItem::lineAmount)
                .containsExactly(tuple(3, 17700L));
        assertThat(quote.totalAmount()).isEqualTo(17700L);
    }

    @Test
    void 재고보다_많이_바꾸면_예외가_발생한다() {
        // given
        givenQuote(List.of(item(1L, 5900L, 1)), List.of());
        given(inventoryService.findAvailableQuantities(List.of(1L))).willReturn(Map.of(1L, 2));

        // when & then
        assertThatThrownBy(() -> quoteCommandService.changeQuantity(1L, 5L, 1L, 3))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    void 수량이_1보다_작으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> quoteCommandService.changeQuantity(1L, 5L, 1L, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_QUANTITY);
    }

    @Test
    void 재료를_추가하면_고른_상품을_담는다() {
        // given
        givenQuote(List.of(item(9L, 3000L, 1)), List.of(new ExcludedItem("김치", "OUT_OF_STOCK")));
        givenProducts(List.of(product(1L, kimchi, "포기김치 500g", 5900L, 500, 100)), Map.of(1L, 100), Map.of());

        // when
        Quote quote = quoteCommandService.addItem(1L, 5L, "김치", 400, BaseUnit.G);

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(9L, 1L);
        assertThat(quote.excludedItems()).isEmpty();
        assertThat(quote.totalAmount()).isEqualTo(8900L);
    }

    @Test
    void 이미_담긴_상품이면_수량을_합친다() {
        // given
        givenQuote(List.of(item(1L, 5900L, 1)), List.of());
        givenProducts(List.of(product(1L, kimchi, "포기김치 500g", 5900L, 500, 100)), Map.of(1L, 100), Map.of());

        // when
        Quote quote = quoteCommandService.addItem(1L, 5L, "김치", 1000, BaseUnit.G);

        // then
        assertThat(quote.items()).extracting(QuoteItem::quantity).containsExactly(3);
        assertThat(quote.totalAmount()).isEqualTo(17700L);
    }

    @Test
    void 합친_수량이_재고보다_많으면_기존_수량을_두고_품절로_돌려준다() {
        // given
        givenQuote(List.of(item(1L, 5900L, 1)), List.of());
        givenProducts(List.of(product(1L, kimchi, "포기김치 500g", 5900L, 500, 100)), Map.of(1L, 2), Map.of());

        // when
        Quote quote = quoteCommandService.addItem(1L, 5L, "김치", 1000, BaseUnit.G);

        // then
        assertThat(quote.items()).extracting(QuoteItem::quantity).containsExactly(1);
        assertThat(quote.excludedItems()).extracting(ExcludedItem::name, ExcludedItem::reason)
                .containsExactly(tuple("김치", "OUT_OF_STOCK"));
    }

    @Test
    void 못_담는_재료는_제외_목록에_넣어서_돌려준다() {
        // given
        givenQuote(List.of(item(1L, 5900L, 1)), List.of());
        givenProducts(List.of(), Map.of(), Map.of());

        // when
        Quote quote = quoteCommandService.addItem(1L, 5L, "김가루", 10, BaseUnit.G);

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(1L);
        assertThat(quote.excludedItems()).extracting(ExcludedItem::name, ExcludedItem::reason)
                .containsExactly(tuple("김가루", "PRODUCT_NOT_FOUND"));
    }

    private void givenQuote(List<QuoteItem> items, List<ExcludedItem> excludedItems) {
        long total = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.of(new Quote("q-1", 1L, 5L, "김치볶음밥",
                items, excludedItems, total, LocalDateTime.now(), LocalDateTime.now().plusMinutes(10))));
    }

    private QuoteItem item(Long productId, Long price, int quantity) {
        return new QuoteItem(productId, "P-" + productId, "상품" + productId, "1개", price, quantity, price * quantity);
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