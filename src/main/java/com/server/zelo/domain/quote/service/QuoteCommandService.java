package com.server.zelo.domain.quote.service;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.BudgetOption;
import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.model.QuoteItemReason;
import com.server.zelo.domain.quote.model.QuoteRecommendation;
import com.server.zelo.domain.quote.model.SubstituteItem;
import com.server.zelo.domain.quote.repository.QuoteRepository;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import com.server.zelo.domain.review.service.ReviewService;
import com.server.zelo.domain.user.service.UserBlockedCategoryService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final BusinessMetrics businessMetrics;
    private final QuoteRepository quoteRepository;
    private final InventoryService inventoryService;
    private final UserBlockedCategoryService userBlockedCategoryService;

    @Transactional(readOnly = true)
    public Quote createQuote(Long userId, Long roomId, MenuPlan menuPlan) {
        Set<Keyword> conditions = new HashSet<>(menuPlan.conditions());
        Set<Long> blockedCategoryIds = userBlockedCategoryService.findBlockedCategoryIds(userId);
        Quote quote = businessMetrics.time(BusinessMetric.QUOTE_CREATE_TIME, () -> {
            Selection selection = select(menuPlan.ingredients(), conditions, blockedCategoryIds);
            List<BudgetOption> budgetOptions = selection.items().stream()
                    .filter(item -> selection.cheaperItems().containsKey(item.productId()))
                    .map(item -> new BudgetOption(item.productId(), selection.cheaperItems().get(item.productId())))
                    .toList();
            return save(userId, roomId, menuPlan.menuName(), selection.items(), selection.excludedItems(), conditions,
                    selection.substitutes(), menuPlan.budget(), budgetOptions);
        });
        businessMetrics.count(BusinessMetric.QUOTE_CREATED);
        quote.excludedItems().forEach(item -> businessMetrics.count(BusinessMetric.QUOTE_EXCLUDED, "reason", item.reason()));
        return quote;
    }

    public Quote removeItem(Long userId, Long roomId, Long productId) {
        Quote quote = getQuote(userId, roomId);
        QuoteItem target = getItem(quote, productId);
        List<QuoteItem> items = quote.items().stream().filter(item -> item != target).toList();
        return save(userId, roomId, quote.menuName(), items, quote.excludedItems(), quote.conditions(), quote.substitutes(),
                quote.budget(), keepOptions(quote, items));
    }

    @Transactional(readOnly = true)
    public Quote changeQuantity(Long userId, Long roomId, Long productId, Integer quantity) {
        if (quantity == null || quantity < 1) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        }
        Quote quote = getQuote(userId, roomId);
        QuoteItem target = getItem(quote, productId);
        if (inventoryService.findAvailableQuantities(List.of(productId)).getOrDefault(productId, 0) < quantity) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }
        List<QuoteItem> items = quote.items().stream()
                .map(item -> item == target ? withQuantity(item, quantity) : item)
                .toList();
        return save(userId, roomId, quote.menuName(), items, quote.excludedItems(), quote.conditions(), quote.substitutes(),
                quote.budget(), keepOptions(quote, items));
    }

    // 못 담으면 예외 대신 excludedItems에 사유를 넣어서 돌려줌 (발급 때랑 똑같이)
    @Transactional(readOnly = true)
    public Quote addItem(Long userId, Long roomId, String ingredientName, Integer amount, BaseUnit unit) {
        if (amount == null || amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Quote quote = getQuote(userId, roomId);
        // 처음 견적의 조건(맵지 않게 등)으로 골라야 추가한 재료도 같은 기준이 됨
        Selection selection = select(List.of(MenuPlanIngredient.of(ingredientName, amount, unit)), quote.conditions(),
                userBlockedCategoryService.findBlockedCategoryIds(userId));

        List<QuoteItem> items = new ArrayList<>(quote.items());
        List<ExcludedItem> excludedItems = new ArrayList<>(quote.excludedItems().stream()
                .filter(excluded -> !excluded.name().equals(ingredientName))
                .toList());
        excludedItems.addAll(selection.excludedItems());
        List<BudgetOption> addedOptions = new ArrayList<>();
        for (QuoteItem added : selection.items()) {
            QuoteItem same = items.stream().filter(item -> item.productId().equals(added.productId())).findFirst().orElse(null);
            if (same == null) {
                items.add(added);
                // 새로 담은 상품도 예산을 넘기면 더 싼 후보를 줘야 해서 같이 챙김
                QuoteItem cheaper = selection.cheaperItems().get(added.productId());
                if (cheaper != null) {
                    addedOptions.add(new BudgetOption(added.productId(), cheaper));
                }
                continue;
            }
            // select()는 이번에 더할 수량만 재고랑 비교해서 합친 수량으로 한 번 더 확인
            int quantity = same.quantity() + added.quantity();
            if (inventoryService.findAvailableQuantities(List.of(same.productId())).getOrDefault(same.productId(), 0) < quantity) {
                excludedItems.add(new ExcludedItem(ingredientName, "OUT_OF_STOCK"));
            } else {
                items.set(items.indexOf(same), withQuantity(same, quantity));
            }
        }
        List<SubstituteItem> substitutes = new ArrayList<>(quote.substitutes().stream()
                .filter(substitute -> !substitute.name().equals(ingredientName))
                .toList());
        substitutes.addAll(selection.substitutes());
        List<BudgetOption> budgetOptions = new ArrayList<>(keepOptions(quote, items));
        budgetOptions.addAll(addedOptions);
        return save(userId, roomId, quote.menuName(), items, excludedItems, quote.conditions(), substitutes,
                quote.budget(), budgetOptions);
    }

    // 계산만 하고 견적은 안 바꿈. 사용자가 담기를 누르면 addItem으로 담음
    @Transactional(readOnly = true)
    public QuoteRecommendation recommendItems(Long userId, Long roomId, List<MenuPlanIngredient> ingredients) {
        Quote quote = getQuote(userId, roomId);
        Selection selection = select(ingredients, quote.conditions(), userBlockedCategoryService.findBlockedCategoryIds(userId));
        long extraAmount = selection.items().stream().mapToLong(QuoteItem::lineAmount).sum();
        return new QuoteRecommendation(selection.items(), selection.substitutes(), selection.excludedItems(), extraAmount);
    }

    // 후보를 보여준 사이에 팔렸을 수 있어서 재고를 다시 봄. 모자라면 addItem처럼 품절로 돌려줌
    @Transactional(readOnly = true)
    public Quote acceptSubstitute(Long userId, Long roomId, String name) {
        Quote quote = getQuote(userId, roomId);
        SubstituteItem target = getSubstitute(quote, name);
        QuoteItem added = target.item();

        List<QuoteItem> items = new ArrayList<>(quote.items());
        List<ExcludedItem> excludedItems = new ArrayList<>(quote.excludedItems());
        QuoteItem same = items.stream().filter(item -> item.productId().equals(added.productId())).findFirst().orElse(null);
        int quantity = same == null ? added.quantity() : same.quantity() + added.quantity();
        if (inventoryService.findAvailableQuantities(List.of(added.productId())).getOrDefault(added.productId(), 0) < quantity) {
            excludedItems.add(new ExcludedItem(name, "OUT_OF_STOCK"));
        } else if (same == null) {
            items.add(added);
        } else {
            items.set(items.indexOf(same), withQuantity(same, quantity));
        }
        return save(userId, roomId, quote.menuName(), items, excludedItems, quote.conditions(), without(quote, target),
                quote.budget(), keepOptions(quote, items));
    }

    public Quote rejectSubstitute(Long userId, Long roomId, String name) {
        Quote quote = getQuote(userId, roomId);
        SubstituteItem target = getSubstitute(quote, name);
        List<ExcludedItem> excludedItems = new ArrayList<>(quote.excludedItems());
        // 사용자가 뺀 거라 재고 부족과 구분함. 채팅 쪽도 같은 이름으로 처리함
        excludedItems.add(new ExcludedItem(name, "REJECTED_BY_USER"));
        return save(userId, roomId, quote.menuName(), quote.items(), excludedItems, quote.conditions(),
                without(quote, target), quote.budget(), quote.budgetOptions());
    }

    // 고른 상품 하나만 더 싼 걸로 바꿈. 나머지 상품 후보는 그대로 둠
    @Transactional(readOnly = true)
    public Quote applyBudgetOption(Long userId, Long roomId, Long productId) {
        Quote quote = getQuote(userId, roomId);
        BudgetOption target = quote.budgetOptions().stream()
                .filter(option -> option.productId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        QuoteItem cheaper = target.item();
        if (inventoryService.findAvailableQuantities(List.of(cheaper.productId())).getOrDefault(cheaper.productId(), 0) < cheaper.quantity()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }
        // 같은 상품이 두 줄이어도 재고는 한 줄 수량만 봤으니 한 줄만 바꿈
        QuoteItem replaced = getItem(quote, productId);
        List<QuoteItem> items = quote.items().stream()
                .map(item -> item == replaced ? cheaper : item)
                .toList();
        return save(userId, roomId, quote.menuName(), items, quote.excludedItems(), quote.conditions(), quote.substitutes(),
                quote.budget(), keepOptions(quote, items));
    }

    private Selection select(List<MenuPlanIngredient> requested, Set<Keyword> conditions, Set<Long> blockedCategoryIds) {
        // 같은 재료가 두 번 오면 양을 합쳐서 한 줄로 고름. 같은 상품이 두 줄 생기면 후보가 어느 줄 건지 꼬여서
        Map<String, MenuPlanIngredient> merged = new LinkedHashMap<>();
        requested.forEach(ingredient -> merged.merge(ingredient.name(), ingredient,
                (first, second) -> MenuPlanIngredient.of(first.name(), first.amount() + second.amount(), first.unit())));
        List<MenuPlanIngredient> ingredients = List.copyOf(merged.values());
        List<String> ingredientNames = ingredients.stream().map(MenuPlanIngredient::name).toList();
        List<Product> products = productService.findOnSaleProducts(ingredientNames);
        List<Long> productIds = products.stream().map(Product::getId).toList();

        Map<Long, Integer> stocks = inventoryService.findAvailableQuantities(productIds);
        Map<Long, Set<Keyword>> keywords = productService.findKeywords(productIds);
        Map<Long, ProductReviewEvidence> reviews = reviewService.findReviewEvidences(productIds, conditions);

        List<QuoteItem> items = new ArrayList<>();
        List<ExcludedItem> excludedItems = new ArrayList<>();
        List<SubstituteItem> substitutes = new ArrayList<>();
        Map<Long, QuoteItem> cheaperItems = new HashMap<>();
        for (MenuPlanIngredient ingredient : ingredients) {
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
            // 사용자가 막은 카테고리(알레르기 등) 상품은 안 고름
            List<Product> allowed = candidates.stream()
                    .filter(product -> !isBlocked(product.getCategory(), blockedCategoryIds))
                    .toList();
            if (allowed.isEmpty()) {
                excludedItems.add(new ExcludedItem(ingredient.name(), "BLOCKED_BY_USER"));
                continue;
            }
            List<Product> inStock = allowed.stream()
                    .filter(product -> stocks.getOrDefault(product.getId(), 0) >= quantity(ingredient, product))
                    .toList();
            if (inStock.isEmpty()) {
                excludedItems.add(new ExcludedItem(ingredient.name(), "OUT_OF_STOCK"));
                continue;
            }

            // 재고 상관없이 원래 고를 상품부터 정함. 그게 품절이면 대신 고른 건 사용자가 고를 때까지 후보로만 둠
            Product first = pick(ingredient, allowed, conditions, keywords, reviews);
            boolean firstInStock = stocks.getOrDefault(first.getId(), 0) >= quantity(ingredient, first);
            Product product = firstInStock ? first : pick(ingredient, inStock, conditions, keywords, reviews);
            QuoteItem item = toItem(ingredient, product, allowed, conditions, keywords, reviews);
            if (!firstInStock) {
                substitutes.add(new SubstituteItem(ingredient.name(), first.getName(), item));
                continue;
            }
            items.add(item);
            findCheaper(ingredient, product, inStock, conditions, keywords).ifPresent(cheaper -> cheaperItems.put(
                    product.getId(), toItem(ingredient, cheaper, allowed, conditions, keywords, reviews)));
        }
        return new Selection(items, excludedItems, substitutes, cheaperItems);
    }

    private QuoteItem toItem(MenuPlanIngredient ingredient, Product product, List<Product> candidates, Set<Keyword> conditions,
                             Map<Long, Set<Keyword>> keywords, Map<Long, ProductReviewEvidence> reviews) {
        int quantity = quantity(ingredient, product);
        Set<Keyword> matched = new HashSet<>(keywords.getOrDefault(product.getId(), Set.of()));
        matched.retainAll(conditions);
        // 품절 상품까지 포함해서 봐야 "조건 상품이 품절이라 다른 걸 골랐다"를 알 수 있음
        boolean conditionProductExists = candidates.stream()
                .anyMatch(candidate -> !Collections.disjoint(keywords.getOrDefault(candidate.getId(), Set.of()), conditions));
        QuoteItemReason reason = QuoteItemReason.of(product.getSalesCount(), reviews.get(product.getId()),
                matched, conditionProductExists);
        return new QuoteItem(product.getId(), product.getProductCode(), product.getName(),
                product.getUnitLabel(), product.getPrice(), quantity, product.getPrice() * quantity, reason);
    }

    // 고른 상품이 맞춘 조건은 더 싼 것도 다 맞춰야 함 (예산 때문에 매운 김치로 바뀌면 안 돼서)
    private Optional<Product> findCheaper(MenuPlanIngredient ingredient, Product picked, List<Product> inStock,
                                          Set<Keyword> conditions, Map<Long, Set<Keyword>> keywords) {
        Set<Keyword> pickedMatched = new HashSet<>(keywords.getOrDefault(picked.getId(), Set.of()));
        pickedMatched.retainAll(conditions);
        return inStock.stream()
                .filter(product -> keywords.getOrDefault(product.getId(), Set.of()).containsAll(pickedMatched))
                .filter(product -> totalPrice(ingredient, product) < totalPrice(ingredient, picked))
                .min(Comparator.comparingLong((Product product) -> totalPrice(ingredient, product))
                        .thenComparing(Product::getId));
    }

    // 대분류를 막으면 그 아래 소분류 상품도 막힘
    private boolean isBlocked(Category category, Set<Long> blockedCategoryIds) {
        return blockedCategoryIds.contains(category.getId())
                || (category.getParent() != null && blockedCategoryIds.contains(category.getParent().getId()));
    }

    // 수정할 때마다 quoteId를 새로 발급해서 수정 전 카드로는 주문 못 하게 함
    private Quote save(Long userId, Long roomId, String menuName, List<QuoteItem> items, List<ExcludedItem> excludedItems,
                       Set<Keyword> conditions, List<SubstituteItem> substitutes, Long budget,
                       List<BudgetOption> budgetOptions) {
        long totalAmount = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        // 예산 안이어도 후보는 지우지 않음. 재료를 더 담아서 넘으면 다시 보여줘야 해서 (보여줄지는 overBudget)
        List<BudgetOption> options = budget != null ? budgetOptions : List.of();
        LocalDateTime now = LocalDateTime.now();
        Quote quote = new Quote(UUID.randomUUID().toString(), userId, roomId, menuName, items, excludedItems,
                totalAmount, now, now.plus(QuoteRepository.QUOTE_TTL), conditions, substitutes, budget, options);
        quoteRepository.save(quote);
        return quote;
    }

    private Quote getQuote(Long userId, Long roomId) {
        Quote quote = quoteRepository.findByRoomId(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUOTE_EXPIRED));
        if (!quote.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTHORITY);
        }
        return quote;
    }

    private QuoteItem getItem(Quote quote, Long productId) {
        return quote.items().stream()
                .filter(item -> item.productId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    private SubstituteItem getSubstitute(Quote quote, String name) {
        return quote.substitutes().stream()
                .filter(substitute -> substitute.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    private List<SubstituteItem> without(Quote quote, SubstituteItem target) {
        return quote.substitutes().stream().filter(substitute -> substitute != target).toList();
    }

    // 바뀐 상품의 후보는 수량이 안 맞아서 버림 (안 바뀐 상품은 같은 QuoteItem이라 contains로 확인)
    private List<BudgetOption> keepOptions(Quote quote, List<QuoteItem> items) {
        return quote.budgetOptions().stream()
                .filter(option -> quote.items().stream()
                        .anyMatch(item -> item.productId().equals(option.productId()) && items.contains(item)))
                .toList();
    }

    private QuoteItem withQuantity(QuoteItem item, int quantity) {
        return new QuoteItem(item.productId(), item.productCode(), item.productName(), item.productUnit(),
                item.unitPrice(), quantity, item.unitPrice() * quantity, item.reason());
    }

    // 필요한 양을 상품 1개 용량으로 나눠서 올림 (김치 400g인데 500g짜리면 1개)
    private int quantity(MenuPlanIngredient ingredient, Product product) {
        return (ingredient.amount() + product.getContentAmount() - 1) / product.getContentAmount();
    }

    // 조건 키워드가 붙은 상품이 있으면 그중에서만 고름 (아이 있는 집인데 매운 김치가 점수로 이기면 안 돼서)
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

    private record Selection(List<QuoteItem> items, List<ExcludedItem> excludedItems, List<SubstituteItem> substitutes,
                             Map<Long, QuoteItem> cheaperItems) {
    }
}