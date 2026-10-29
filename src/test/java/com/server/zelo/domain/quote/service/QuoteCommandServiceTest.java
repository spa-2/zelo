package com.server.zelo.domain.quote.service;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.product.entity.*;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.*;
import com.server.zelo.domain.quote.repository.QuoteRepository;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import com.server.zelo.domain.review.service.ReviewService;
import com.server.zelo.domain.user.service.UserBlockedCategoryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

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

    @Mock
    private UserBlockedCategoryService userBlockedCategoryService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);

    @InjectMocks
    private QuoteCommandService quoteCommandService;

    private final Ingredient kimchi = Ingredient.of(Category.of(Category.of("국·반찬", true), "김치·젓갈"), "김치", BaseUnit.G);
    private final Ingredient soju = Ingredient.of(Category.of(Category.of("주류", false), "소주·전통주"), "소주", BaseUnit.ML);
    private final Ingredient egg = Ingredient.of(Category.of(Category.of("계란·유제품", true), "계란"), "계란", BaseUnit.EA);

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
    void 견적을_만들면_발급_수와_빠진_사유를_센다() {
        // given
        Product sojuProduct = product(1L, soju, "소주 360ml", 1900L, 360, 100);
        Product pogi = product(2L, kimchi, "포기김치 500g", 5900L, 500, 100);
        givenProducts(List.of(sojuProduct, pogi), Map.of(1L, 100, 2L, 100), Map.of());

        // when
        quoteCommandService.createQuote(1L, 5L, menuPlan(List.of(
                MenuPlanIngredient.of("소주", 360, BaseUnit.ML),
                MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of()));

        // then
        assertThat(meterRegistry.get("zelo.quote.created").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("zelo.quote.excluded").tag("reason", "CATEGORY_NOT_ALLOWED").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("zelo.quote.create.time").timer().count()).isEqualTo(1);
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
                2L, new BigDecimal("4.6"), 23, Map.of(Keyword.NOT_SPICY, 15), "맵지 않아 아이들도 잘 먹는다는 평이 많아요")));

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L,
                menuPlan(List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of(Keyword.NOT_SPICY)));

        // then
        QuoteItemReason reason = quote.items().get(0).reason();
        assertThat(reason.salesCount()).isEqualTo(80);
        assertThat(reason.avgRating()).isEqualByComparingTo("4.6");
        assertThat(reason.reviewCount()).isEqualTo(23);
        assertThat(reason.keywordMentions()).containsEntry(Keyword.NOT_SPICY, 15);
        assertThat(reason.summary()).isEqualTo("맵지 않아 아이들도 잘 먹는다는 평이 많아요");
        assertThat(reason.matchedConditions()).containsExactly(Keyword.NOT_SPICY);
        assertThat(reason.conditionProductExists()).isTrue();
        assertThat(quote.conditions()).containsExactly(Keyword.NOT_SPICY);
    }

    @Test
    void 원래_고를_상품이_품절이면_대신_고른_상품은_후보로만_돌려준다() {
        // given
        Product pogi = product(1L, kimchi, "포기김치 500g", 5900L, 500, 120);
        Product baek = product(2L, kimchi, "백김치 1kg", 8900L, 1000, 80);
        givenProducts(List.of(pogi, baek), Map.of(1L, 100, 2L, 0), Map.of(2L, Set.of(Keyword.NOT_SPICY)));

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L,
                menuPlan(List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of(Keyword.NOT_SPICY)));

        // then
        assertThat(quote.items()).isEmpty();
        assertThat(quote.totalAmount()).isZero();
        assertThat(quote.substitutes()).extracting(SubstituteItem::name, SubstituteItem::soldOutProductName)
                .containsExactly(tuple("김치", "백김치 1kg"));
        QuoteItem substitute = quote.substitutes().get(0).item();
        assertThat(substitute.productName()).isEqualTo("포기김치 500g");
        assertThat(substitute.reason().matchedConditions()).isEmpty();
        assertThat(substitute.reason().conditionProductExists()).isTrue();
    }

    @Test
    void 후보를_고르면_견적에_담고_후보에서_뺀다() {
        // given
        givenSubstituteQuote(List.of(item(9L, 3000L, 1)));
        given(inventoryService.findAvailableQuantities(List.of(1L))).willReturn(Map.of(1L, 10));

        // when
        Quote quote = quoteCommandService.acceptSubstitute(1L, 5L, "김치");

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(9L, 1L);
        assertThat(quote.substitutes()).isEmpty();
        assertThat(quote.totalAmount()).isEqualTo(8900L);
        assertThat(quote.quoteId()).isNotEqualTo("q-1");
    }

    @Test
    void 후보를_고를_때_이미_담긴_상품이면_수량을_합친다() {
        // given
        givenSubstituteQuote(List.of(item(1L, 5900L, 1)));
        given(inventoryService.findAvailableQuantities(List.of(1L))).willReturn(Map.of(1L, 10));

        // when
        Quote quote = quoteCommandService.acceptSubstitute(1L, 5L, "김치");

        // then
        assertThat(quote.items()).extracting(QuoteItem::quantity).containsExactly(2);
    }

    @Test
    void 후보를_고르는_사이에_품절되면_품절로_돌려준다() {
        // given
        givenSubstituteQuote(List.of());
        given(inventoryService.findAvailableQuantities(List.of(1L))).willReturn(Map.of(1L, 0));

        // when
        Quote quote = quoteCommandService.acceptSubstitute(1L, 5L, "김치");

        // then
        assertThat(quote.items()).isEmpty();
        assertThat(quote.substitutes()).isEmpty();
        assertThat(quote.excludedItems()).extracting(ExcludedItem::name, ExcludedItem::reason)
                .containsExactly(tuple("김치", "OUT_OF_STOCK"));
    }

    @Test
    void 후보를_빼면_사용자가_뺀_것으로_제외한다() {
        // given
        givenSubstituteQuote(List.of(item(9L, 3000L, 1)));

        // when
        Quote quote = quoteCommandService.rejectSubstitute(1L, 5L, "김치");

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(9L);
        assertThat(quote.substitutes()).isEmpty();
        assertThat(quote.excludedItems()).extracting(ExcludedItem::name, ExcludedItem::reason)
                .containsExactly(tuple("김치", "REJECTED_BY_USER"));
    }

    @Test
    void 없는_후보를_고르면_예외가_발생한다() {
        // given
        givenSubstituteQuote(List.of());

        // when & then
        assertThatThrownBy(() -> quoteCommandService.acceptSubstitute(1L, 5L, "계란"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    void 재료를_다시_추가하면_그_재료의_이전_후보는_지운다() {
        // given
        givenSubstituteQuote(List.of());
        givenProducts(List.of(product(2L, kimchi, "백김치 1kg", 8900L, 1000, 80)), Map.of(2L, 100), Map.of());

        // when
        Quote quote = quoteCommandService.addItem(1L, 5L, "김치", 400, BaseUnit.G);

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(2L);
        assertThat(quote.substitutes()).isEmpty();
    }

    @Test
    void 재료를_추가할_때도_처음_견적의_조건으로_고른다() {
        // given
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.of(new Quote("q-1", 1L, 5L, "김치볶음밥",
                List.of(), List.of(), 0L, LocalDateTime.now(), LocalDateTime.now().plusMinutes(10), Set.of(Keyword.NOT_SPICY))));
        Product pogi = product(1L, kimchi, "포기김치 500g", 5900L, 500, 120);
        Product baek = product(2L, kimchi, "백김치 1kg", 8900L, 1000, 80);
        givenProducts(List.of(pogi, baek), Map.of(1L, 100, 2L, 100), Map.of(2L, Set.of(Keyword.NOT_SPICY)));

        // when
        Quote quote = quoteCommandService.addItem(1L, 5L, "김치", 400, BaseUnit.G);

        // then
        assertThat(quote.items()).extracting(QuoteItem::productName).containsExactly("백김치 1kg");
        assertThat(quote.items().get(0).reason().matchedConditions()).containsExactly(Keyword.NOT_SPICY);
        assertThat(quote.conditions()).containsExactly(Keyword.NOT_SPICY);
    }

    @Test
    void 조건_상품이_아예_없으면_조건과_상관없는_재료로_담고_리뷰가_없으면_비워둔다() {
        // given
        givenProducts(List.of(product(1L, kimchi, "포기김치 500g", 5900L, 500, 120)), Map.of(1L, 100), Map.of());

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L,
                menuPlan(List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of(Keyword.NOT_SPICY)));

        // then
        QuoteItemReason reason = quote.items().get(0).reason();
        assertThat(reason.matchedConditions()).isEmpty();
        assertThat(reason.conditionProductExists()).isFalse();
        assertThat(reason.avgRating()).isNull();
        assertThat(reason.reviewCount()).isZero();
        assertThat(reason.keywordMentions()).isEmpty();
        assertThat(reason.summary()).isNull();
    }
    @Test
    void 수량을_바꿔도_근거는_그대로_둔다() {
        // given
        QuoteItemReason reason = new QuoteItemReason(80, new BigDecimal("4.6"), 23, Map.of(), null, Set.of(), false);
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
    @Test
    void 막은_카테고리_상품은_고르지_않고_사용자_설정으로_제외한다() {
        // given
        Category side = category(1L, "국·반찬", null);
        Product pogi = product(1L, Ingredient.of(category(2L, "김치·젓갈", side), "김치", BaseUnit.G), "포기김치 500g", 5900L, 500, 100);
        givenProducts(List.of(pogi), Map.of(1L, 100), Map.of());
        given(userBlockedCategoryService.findBlockedCategoryIds(1L)).willReturn(Set.of(2L));

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L,
                menuPlan(List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of()));

        // then
        assertThat(quote.items()).isEmpty();
        assertThat(quote.excludedItems()).extracting(ExcludedItem::name, ExcludedItem::reason)
                .containsExactly(tuple("김치", "BLOCKED_BY_USER"));
    }

    @Test
    void 대분류를_막으면_그_아래_소분류_상품도_고르지_않는다() {
        // given
        Category side = category(1L, "국·반찬", null);
        Product pogi = product(1L, Ingredient.of(category(2L, "김치·젓갈", side), "김치", BaseUnit.G), "포기김치 500g", 5900L, 500, 100);
        Product tofu = product(2L, Ingredient.of(category(3L, "두부·콩", category(4L, "가공식품", null)), "두부", BaseUnit.G),
                "두부 300g", 1500L, 300, 100);
        givenProducts(List.of(pogi, tofu), Map.of(1L, 100, 2L, 100), Map.of());
        given(userBlockedCategoryService.findBlockedCategoryIds(1L)).willReturn(Set.of(1L));

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L, menuPlan(List.of(
                MenuPlanIngredient.of("김치", 400, BaseUnit.G), MenuPlanIngredient.of("두부", 300, BaseUnit.G)), List.of()));

        // then
        assertThat(quote.items()).extracting(QuoteItem::productName).containsExactly("두부 300g");
        assertThat(quote.excludedItems()).extracting(ExcludedItem::name, ExcludedItem::reason)
                .containsExactly(tuple("김치", "BLOCKED_BY_USER"));
    }

    @Test
    void 재료를_추가할_때도_막은_카테고리는_고르지_않는다() {
        // given
        givenQuote(List.of(item(9L, 3000L, 1)), List.of());
        Product pogi = product(1L, Ingredient.of(category(2L, "김치·젓갈", category(1L, "국·반찬", null)), "김치", BaseUnit.G),
                "포기김치 500g", 5900L, 500, 100);
        givenProducts(List.of(pogi), Map.of(1L, 100), Map.of());
        given(userBlockedCategoryService.findBlockedCategoryIds(1L)).willReturn(Set.of(2L));

        // when
        Quote quote = quoteCommandService.addItem(1L, 5L, "김치", 400, BaseUnit.G);

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(9L);
        assertThat(quote.excludedItems()).extracting(ExcludedItem::reason).containsExactly("BLOCKED_BY_USER");
    }
    @Test
    void 추천_재료의_상품과_추가_금액을_계산하고_견적은_저장하지_않는다() {
        // given
        givenQuote(List.of(item(9L, 3000L, 1)), List.of());
        givenProducts(List.of(product(1L, kimchi, "포기김치 500g", 5900L, 500, 100)), Map.of(1L, 100), Map.of());

        // when
        QuoteRecommendation recommendation = quoteCommandService.recommendItems(1L, 5L,
                List.of(MenuPlanIngredient.of("김치", 800, BaseUnit.G)));

        // then
        assertThat(recommendation.items()).extracting(QuoteItem::productName, QuoteItem::quantity)
                .containsExactly(tuple("포기김치 500g", 2));
        assertThat(recommendation.extraAmount()).isEqualTo(11800L);
        then(quoteRepository).should(never()).save(any());
    }

    @Test
    void 추천_재료도_처음_견적의_조건으로_고른다() {
        // given
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.of(new Quote("q-1", 1L, 5L, "김치볶음밥",
                List.of(), List.of(), 0L, LocalDateTime.now(), LocalDateTime.now().plusMinutes(10), Set.of(Keyword.NOT_SPICY))));
        Product pogi = product(1L, kimchi, "포기김치 500g", 5900L, 500, 120);
        Product baek = product(2L, kimchi, "백김치 1kg", 8900L, 1000, 80);
        givenProducts(List.of(pogi, baek), Map.of(1L, 100, 2L, 100), Map.of(2L, Set.of(Keyword.NOT_SPICY)));

        // when
        QuoteRecommendation recommendation = quoteCommandService.recommendItems(1L, 5L,
                List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)));

        // then
        assertThat(recommendation.items()).extracting(QuoteItem::productName).containsExactly("백김치 1kg");
    }

    @Test
    void 못_담는_추천_재료는_사유와_같이_돌려주고_추가_금액에_넣지_않는다() {
        // given
        givenQuote(List.of(), List.of());
        Product pogi = product(1L, kimchi, "포기김치 500g", 5900L, 500, 120);
        Product baek = product(2L, kimchi, "백김치 1kg", 8900L, 1000, 80);
        givenProducts(List.of(pogi, baek), Map.of(1L, 0, 2L, 100), Map.of());

        // when
        QuoteRecommendation recommendation = quoteCommandService.recommendItems(1L, 5L, List.of(
                MenuPlanIngredient.of("참치캔", 1, BaseUnit.EA),
                MenuPlanIngredient.of("김치", 400, BaseUnit.G)));

        // then
        assertThat(recommendation.items()).isEmpty();
        assertThat(recommendation.excludedItems()).extracting(ExcludedItem::name, ExcludedItem::reason)
                .containsExactly(tuple("참치캔", "PRODUCT_NOT_FOUND"));
        assertThat(recommendation.substitutes()).extracting(SubstituteItem::soldOutProductName)
                .containsExactly("포기김치 500g");
        assertThat(recommendation.extraAmount()).isZero();
    }

    @Test
    void 견적이_없으면_추천도_만료_예외가_발생한다() {
        // given
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> quoteCommandService.recommendItems(1L, 5L,
                List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.QUOTE_EXPIRED);
    }

    @Test
    void 예산을_넘으면_상품마다_더_싼_후보를_준다() {
        // given
        Product pogi = product(1L, kimchi, "포기김치 1kg", 12000L, 1000, 100);
        Product cheapKimchi = product(2L, kimchi, "썰은김치 1kg", 9000L, 1000, 10);
        Product eggs = product(3L, egg, "무항생제 계란 10구", 5000L, 10, 100);
        Product cheapEggs = product(4L, egg, "계란 10구", 3500L, 10, 10);
        givenProducts(List.of(pogi, cheapKimchi, eggs, cheapEggs), Map.of(1L, 10, 2L, 10, 3L, 10, 4L, 10), Map.of());

        // when (12,000 + 5,000 = 17,000원, 예산 15,000원)
        Quote quote = quoteCommandService.createQuote(1L, 5L, menuPlan(List.of(
                MenuPlanIngredient.of("김치", 1000, BaseUnit.G),
                MenuPlanIngredient.of("계란", 10, BaseUnit.EA)), List.of(), 15000L));

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(1L, 3L);
        assertThat(quote.budget()).isEqualTo(15000L);
        assertThat(quote.budgetOptions()).extracting(BudgetOption::productId, option -> option.item().productName())
                .containsExactly(tuple(1L, "썰은김치 1kg"), tuple(3L, "계란 10구"));
    }

    @Test
    void 예산_안이면_예산_초과로_보지_않는다() {
        // given
        Product pogi = product(1L, kimchi, "포기김치 1kg", 12000L, 1000, 100);
        Product cheapKimchi = product(2L, kimchi, "썰은김치 1kg", 9000L, 1000, 10);
        givenProducts(List.of(pogi, cheapKimchi), Map.of(1L, 10, 2L, 10), Map.of());

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L,
                menuPlan(List.of(MenuPlanIngredient.of("김치", 1000, BaseUnit.G)), List.of(), 20000L));

        // then
        assertThat(quote.overBudget()).isFalse();
    }

    @Test
    void 조건_상품을_골랐으면_더_싸도_조건_없는_상품은_후보로_안_준다() {
        // given
        Product baek = product(1L, kimchi, "백김치 1kg", 12000L, 1000, 100);
        Product spicy = product(2L, kimchi, "매운 포기김치 1kg", 7000L, 1000, 10);
        Product cheapBaek = product(3L, kimchi, "백김치 실속 1kg", 10000L, 1000, 10);
        givenProducts(List.of(baek, spicy, cheapBaek), Map.of(1L, 10, 2L, 10, 3L, 10),
                Map.of(1L, Set.of(Keyword.NOT_SPICY), 3L, Set.of(Keyword.NOT_SPICY)));

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L, menuPlan(
                List.of(MenuPlanIngredient.of("김치", 1000, BaseUnit.G)), List.of(Keyword.NOT_SPICY), 8000L));

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(1L);
        assertThat(quote.budgetOptions()).extracting(option -> option.item().productId()).containsExactly(3L);
    }

    @Test
    void 예산_후보를_고르면_그_상품만_바꾸고_나머지_후보는_남긴다() {
        // given
        givenBudgetQuote(List.of(item(1L, 12000L, 1), item(3L, 5000L, 1)), 15000L, List.of(
                new BudgetOption(1L, item(2L, 9000L, 1)), new BudgetOption(3L, item(4L, 3500L, 1))));
        given(inventoryService.findAvailableQuantities(List.of(2L))).willReturn(Map.of(2L, 10));

        // when
        Quote quote = quoteCommandService.applyBudgetOption(1L, 5L, 1L);

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(2L, 3L);
        assertThat(quote.totalAmount()).isEqualTo(14000L);
        assertThat(quote.overBudget()).isFalse();
        assertThat(quote.budgetOptions()).extracting(BudgetOption::productId).containsExactly(3L);
        assertThat(quote.quoteId()).isNotEqualTo("q-1");
    }

    @Test
    void 바꿔도_예산을_넘으면_나머지_후보는_남긴다() {
        // given
        givenBudgetQuote(List.of(item(1L, 12000L, 1), item(3L, 5000L, 1)), 10000L, List.of(
                new BudgetOption(1L, item(2L, 9000L, 1)), new BudgetOption(3L, item(4L, 3500L, 1))));
        given(inventoryService.findAvailableQuantities(List.of(2L))).willReturn(Map.of(2L, 10));

        // when
        Quote quote = quoteCommandService.applyBudgetOption(1L, 5L, 1L);

        // then
        assertThat(quote.budgetOptions()).extracting(BudgetOption::productId).containsExactly(3L);
    }

    @Test
    void 수량을_바꾼_상품의_예산_후보는_지운다() {
        // given
        givenBudgetQuote(List.of(item(1L, 12000L, 1), item(3L, 5000L, 1)), 10000L, List.of(
                new BudgetOption(1L, item(2L, 9000L, 1)), new BudgetOption(3L, item(4L, 3500L, 1))));
        given(inventoryService.findAvailableQuantities(List.of(1L))).willReturn(Map.of(1L, 10));

        // when
        Quote quote = quoteCommandService.changeQuantity(1L, 5L, 1L, 2);

        // then
        assertThat(quote.budgetOptions()).extracting(BudgetOption::productId).containsExactly(3L);
    }

    @Test
    void 예산_후보_상품이_품절되면_예외가_발생한다() {
        // given
        givenBudgetQuote(List.of(item(1L, 12000L, 1)), 10000L, List.of(new BudgetOption(1L, item(2L, 9000L, 1))));
        given(inventoryService.findAvailableQuantities(List.of(2L))).willReturn(Map.of(2L, 0));

        // when & then
        assertThatThrownBy(() -> quoteCommandService.applyBudgetOption(1L, 5L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    void 예산_후보가_없는_상품이면_예외가_발생한다() {
        // given
        givenBudgetQuote(List.of(item(1L, 12000L, 1)), 10000L, List.of());

        // when & then
        assertThatThrownBy(() -> quoteCommandService.applyBudgetOption(1L, 5L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }
    @Test
    void 재료를_추가해서_예산을_넘으면_추가한_상품의_더_싼_후보도_준다() {
        // given
        givenBudgetQuote(List.of(item(9L, 3000L, 1)), 10000L, List.of());
        Product pogi = product(1L, kimchi, "포기김치 1kg", 12000L, 1000, 100);
        Product cheapKimchi = product(2L, kimchi, "썰은김치 1kg", 9000L, 1000, 10);
        givenProducts(List.of(pogi, cheapKimchi), Map.of(1L, 10, 2L, 10), Map.of());

        // when
        Quote quote = quoteCommandService.addItem(1L, 5L, "김치", 1000, BaseUnit.G);

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(9L, 1L);
        assertThat(quote.budgetOptions()).extracting(BudgetOption::productId, option -> option.item().productId())
                .containsExactly(tuple(1L, 2L));
    }

    @Test
    void 같은_상품이_두_줄이면_예산_후보를_골라도_한_줄만_바꾼다() {
        // given
        givenBudgetQuote(List.of(item(1L, 12000L, 1), item(1L, 12000L, 1)), 10000L,
                List.of(new BudgetOption(1L, item(2L, 9000L, 1))));
        given(inventoryService.findAvailableQuantities(List.of(2L))).willReturn(Map.of(2L, 1));

        // when
        Quote quote = quoteCommandService.applyBudgetOption(1L, 5L, 1L);

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(2L, 1L);
    }
    @Test
    void 예산_안이던_견적에_재료를_더해서_넘으면_기존_상품_후보도_다시_보여준다() {
        // given
        givenBudgetQuote(List.of(item(1L, 12000L, 1)), 15000L, List.of(new BudgetOption(1L, item(2L, 9000L, 1))));
        givenProducts(List.of(product(3L, egg, "계란 10구", 5000L, 10, 10)), Map.of(3L, 10), Map.of());

        // when
        Quote quote = quoteCommandService.addItem(1L, 5L, "계란", 10, BaseUnit.EA);

        // then
        assertThat(quote.overBudget()).isTrue();
        assertThat(quote.budgetOptions()).extracting(BudgetOption::productId).containsExactly(1L);
    }

    @Test
    void 고른_상품이_맞춘_조건은_더_싼_후보도_다_맞춰야_한다() {
        // given
        Product baek = product(1L, kimchi, "국산 백김치 1kg", 12000L, 1000, 100);
        Product importedBaek = product(2L, kimchi, "수입 백김치 1kg", 7000L, 1000, 10);
        givenProducts(List.of(baek, importedBaek), Map.of(1L, 10, 2L, 10),
                Map.of(1L, Set.of(Keyword.NOT_SPICY, Keyword.DOMESTIC), 2L, Set.of(Keyword.NOT_SPICY)));

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L, menuPlan(List.of(MenuPlanIngredient.of("김치", 1000, BaseUnit.G)),
                List.of(Keyword.NOT_SPICY, Keyword.DOMESTIC), 8000L));

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId).containsExactly(1L);
        assertThat(quote.budgetOptions()).isEmpty();
    }

    @Test
    void 같은_재료가_두_번_오면_양을_합쳐서_한_줄로_담는다() {
        // given
        givenProducts(List.of(product(1L, kimchi, "포기김치 500g", 5900L, 500, 100)), Map.of(1L, 10), Map.of());

        // when
        Quote quote = quoteCommandService.createQuote(1L, 5L, menuPlan(List.of(
                MenuPlanIngredient.of("김치", 400, BaseUnit.G), MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of()));

        // then
        assertThat(quote.items()).extracting(QuoteItem::productId, QuoteItem::quantity).containsExactly(tuple(1L, 2));
    }

    private void givenQuote(List<QuoteItem> items, List<ExcludedItem> excludedItems) {
        long total = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.of(new Quote("q-1", 1L, 5L, "김치볶음밥",
                items, excludedItems, total, LocalDateTime.now(), LocalDateTime.now().plusMinutes(10))));
    }

    private void givenSubstituteQuote(List<QuoteItem> items) {
        long total = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        SubstituteItem substitute = new SubstituteItem("김치", "백김치 1kg", item(1L, 5900L, 1));
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.of(new Quote("q-1", 1L, 5L, "김치볶음밥",
                items, List.of(), total, LocalDateTime.now(), LocalDateTime.now().plusMinutes(10), Set.of(),
                List.of(substitute), null, List.of())));
    }

    private void givenBudgetQuote(List<QuoteItem> items, Long budget, List<BudgetOption> budgetOptions) {
        long total = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.of(new Quote("q-1", 1L, 5L, "김치볶음밥",
                items, List.of(), total, LocalDateTime.now(), LocalDateTime.now().plusMinutes(10), Set.of(),
                List.of(), budget, budgetOptions)));
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
        return menuPlan(ingredients, conditions, null);
    }

    private MenuPlan menuPlan(List<MenuPlanIngredient> ingredients, List<Keyword> conditions, Long budget) {
        return MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 4, budget, ingredients, conditions, List.of());
    }

    private Category category(Long id, String name, Category parent) {
        Category category = parent == null ? Category.of(name, true) : Category.of(parent, name);
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    private Product product(Long id, Ingredient ingredient, String name, Long price, int contentAmount, int salesCount) {
        Product product = Product.of("P-" + id, ingredient, "브랜드 A", name, null, price, contentAmount, "1개");
        ReflectionTestUtils.setField(product, "id", id);
        ReflectionTestUtils.setField(product, "salesCount", salesCount);
        return product;
    }
}