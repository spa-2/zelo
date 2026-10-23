package com.server.zelo.domain.quote.service;

import com.server.zelo.ai.llm.MenuPlan;
import com.server.zelo.ai.llm.MenuPlanIngredient;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.repository.QuoteRepository;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import com.server.zelo.domain.review.service.ReviewService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QuoteCommandService {

    private static final double SALES_WEIGHT = 0.4;
    private static final double REVIEW_WEIGHT = 0.3;
    private static final double PRICE_WEIGHT = 0.3;
    private static final int MIN_REVIEW_COUNT = 5;

    private final ReviewService reviewService;
    private final ProductService productService;
    private final QuoteRepository quoteRepository;
    private final InventoryService inventoryService;

    @Transactional(readOnly = true)
    public Quote createQuote(Long userId, Long roomId, MenuPlan menuPlan) {
        List<String> ingredientNames = menuPlan.ingredients().stream().map(MenuPlanIngredient::name).toList();
        List<Product> products = productService.findOnSaleProducts(ingredientNames);
        List<Long> productIds = products.stream().map(Product::getId).toList();

        Set<Keyword> conditions = new HashSet<>(menuPlan.conditions());
        Map<Long, Integer> stocks = inventoryService.findAvailableQuantities(productIds);
        Map<Long, Set<Keyword>> keywords = productService.findKeywords(productIds);
        Map<Long, ProductReviewEvidence> reviews = reviewService.findReviewEvidences(productIds, conditions);

        List<QuoteItem> items = new ArrayList<>();
        List<ExcludedItem> excludedItems = new ArrayList<>();
        for (MenuPlanIngredient ingredient : menuPlan.ingredients()) {
            List<Product> candidates = products.stream()
                    .filter(product -> product.getIngredient().getName().equals(ingredient.name()))
                    .toList();
            if (candidates.isEmpty()) {
                excludedItems.add(new ExcludedItem(ingredient.name(), "PRODUCT_NOT_FOUND"));
                continue;
            }
            if (!candidates.get(0).getCategory().isAgentOrderable()) {
                excludedItems.add(new ExcludedItem(ingredient.name(), "CATEGORY_NOT_ALLOWED"));
                continue;
            }
            List<Product> inStock = candidates.stream()
                    .filter(product -> stocks.getOrDefault(product.getId(), 0) >= quantity(ingredient, product))
                    .toList();
            if (inStock.isEmpty()) {
                excludedItems.add(new ExcludedItem(ingredient.name(), "OUT_OF_STOCK"));
                continue;
            }

            Product product = pick(ingredient, inStock, conditions, keywords, reviews);
            int quantity = quantity(ingredient, product);
            items.add(new QuoteItem(product.getId(), product.getProductCode(), product.getName(),
                    product.getUnitLabel(), product.getPrice(), quantity, product.getPrice() * quantity));
        }

        long totalAmount = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        LocalDateTime now = LocalDateTime.now();
        Quote quote = new Quote(UUID.randomUUID().toString(), userId, roomId, menuPlan.menuName(),
                items, excludedItems, totalAmount, now, now.plus(QuoteRepository.QUOTE_TTL));
        quoteRepository.save(quote);
        return quote;
    }

    // 필요한 양을 상품 1개 용량으로 나눠서 올림 (필요한 김치 400g인데 500g짜리면 1개)
    private int quantity(MenuPlanIngredient ingredient, Product product) {
        return (ingredient.amount() + product.getContentAmount() - 1) / product.getContentAmount();
    }

    // 조건 키워드가 붙은 상품이 있으면 그중에서만 고름 (ex : 아이 있는 집인데 매운 김치가 점수가 높다는 이유로 선택되면 안되서)
    private Product pick(MenuPlanIngredient ingredient, List<Product> candidates, Set<Keyword> conditions,
                         Map<Long, Set<Keyword>> keywords, Map<Long, ProductReviewEvidence> reviews) {
        List<Product> matched = candidates.stream()
                .filter(product -> !Collections.disjoint(keywords.getOrDefault(product.getId(), Set.of()), conditions))
                .toList();
        List<Product> pool = matched.isEmpty() ? candidates : matched;

        int maxSales = pool.stream().mapToInt(Product::getSalesCount).max().orElse(0);
        long minTotal = pool.stream().mapToLong(product -> totalPrice(ingredient, product)).min().orElse(0);

        return pool.stream()
                .max(Comparator.comparingDouble((Product product) ->
                                score(ingredient, product, maxSales, minTotal, reviews.get(product.getId())))
                        .thenComparing(Product::getSalesCount)
                        .thenComparing(Product::getId, Comparator.reverseOrder()))
                .orElseThrow();
    }

    // 판매량 40 + 리뷰 30 + 가격 30. 가격은 필요한 만큼 샀을 때 총액으로 비교
    private double score(MenuPlanIngredient ingredient, Product product, int maxSales, long minTotal,
                         ProductReviewEvidence review) {
        double sales = maxSales == 0 ? 0 : (double) product.getSalesCount() / maxSales;
        double price = (double) minTotal / totalPrice(ingredient, product);
        return SALES_WEIGHT * sales + REVIEW_WEIGHT * reviewScore(review) + PRICE_WEIGHT * price;
    }

    // 리뷰가 5개 안 되면 평점을 믿기 어려워서 중간값
    private double reviewScore(ProductReviewEvidence review) {
        if (review == null || review.avgRating() == null || review.reviewCount() < MIN_REVIEW_COUNT) {
            return 0.5;
        }
        return review.avgRating().doubleValue() / 5;
    }

    private long totalPrice(MenuPlanIngredient ingredient, Product product) {
        return product.getPrice() * quantity(ingredient, product);
    }
}