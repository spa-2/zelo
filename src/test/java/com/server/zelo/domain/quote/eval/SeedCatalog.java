package com.server.zelo.domain.quote.eval;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Ingredient;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// 운영 시드(seed/*.csv, reviews.json)를 DB 없이 메모리에 올린 상품 카탈로그.
// 시드는 재료당 상품이 최대 66개라 견적 후보 쿼리(재료·조건 묶음별 100개)와 전체 조회가 같은 후보를 준다
class SeedCatalog {

    private final Map<String, Category> categories = new HashMap<>();
    private final List<Product> products = new ArrayList<>();
    private final Map<Long, Integer> stocks = new HashMap<>();
    private final Map<Long, Set<Keyword>> keywords = new HashMap<>();
    private final Map<Long, List<ReviewSeed>> reviews = new HashMap<>();

    record ReviewSeed(String productCode, Integer rating, String content, List<Keyword> keywords) {
    }

    SeedCatalog() {
        long categoryId = 1;
        for (String[] row : readRows("seed/categories.csv")) {
            // parent,name,agentOrderable
            Category category = row[0].isBlank()
                    ? Category.of(row[1], Boolean.parseBoolean(row[2]))
                    : Category.of(categories.get(row[0]), row[1]);
            ReflectionTestUtils.setField(category, "id", categoryId++);
            categories.put(row[1], category);
        }
        Map<String, Ingredient> ingredients = new HashMap<>();
        for (String[] row : readRows("seed/ingredients.csv")) {
            // category,name,baseUnit
            ingredients.put(row[1], Ingredient.of(categories.get(row[0]), row[1], BaseUnit.valueOf(row[2])));
        }
        Map<String, Product> byCode = new HashMap<>();
        long productId = 1;
        for (String[] row : readRows("seed/products.csv")) {
            // productCode,ingredient,brand,name,price,contentAmount,unitLabel,stock,keywords
            Product product = Product.of(row[0], ingredients.get(row[1]), row[2], row[3], null,
                    Long.parseLong(row[4]), Integer.parseInt(row[5]), row[6]);
            ReflectionTestUtils.setField(product, "id", productId++);
            products.add(product);
            byCode.put(product.getProductCode(), product);
            stocks.put(product.getId(), Integer.parseInt(row[7]));
            keywords.put(product.getId(), Arrays.stream(row[8].split(";"))
                    .filter(keyword -> !keyword.isBlank())
                    .map(Keyword::valueOf)
                    .collect(Collectors.toUnmodifiableSet()));
        }
        for (ReviewSeed seed : readReviews()) {
            reviews.computeIfAbsent(byCode.get(seed.productCode()).getId(), id -> new ArrayList<>()).add(seed);
        }
    }

    List<Product> products() {
        return products;
    }

    Category category(String name) {
        return categories.get(name);
    }

    int stock(Long productId) {
        return stocks.getOrDefault(productId, 0);
    }

    Set<Keyword> keywords(Long productId) {
        return keywords.getOrDefault(productId, Set.of());
    }

    // ProductService.findQuoteCandidates 대신: 요청한 재료의 판매중 상품 전부
    List<Product> candidates(Collection<String> ingredientNames) {
        return products.stream()
                .filter(product -> ingredientNames.contains(product.getIngredient().getName()))
                .toList();
    }

    Map<Long, Integer> stocks(Collection<Long> productIds) {
        return productIds.stream().collect(Collectors.toMap(id -> id, this::stock, (first, second) -> first));
    }

    // ProductService.findKeywords처럼 키워드가 없는 상품은 넣지 않는다
    Map<Long, Set<Keyword>> keywords(Collection<Long> productIds) {
        return productIds.stream()
                .filter(id -> !keywords(id).isEmpty())
                .distinct()
                .collect(Collectors.toMap(id -> id, this::keywords));
    }

    // ReviewService.findReviewEvidences와 같은 모양: 평균 평점(소수 둘째 자리 반올림), 리뷰 수, 요청 조건의 언급 수
    Map<Long, ProductReviewEvidence> reviewEvidences(List<Long> productIds, Set<Keyword> conditions) {
        Map<Long, ProductReviewEvidence> evidences = new LinkedHashMap<>();
        for (Long productId : productIds) {
            List<ReviewSeed> seeds = reviews.getOrDefault(productId, List.of());
            Map<Keyword, Integer> mentions = new EnumMap<>(Keyword.class);
            conditions.forEach(condition -> mentions.put(condition, (int) seeds.stream()
                    .filter(seed -> seed.keywords().contains(condition)).count()));
            BigDecimal avgRating = seeds.isEmpty() ? null : BigDecimal.valueOf(seeds.stream().mapToInt(ReviewSeed::rating).sum())
                    .divide(BigDecimal.valueOf(seeds.size()), 2, RoundingMode.HALF_UP);
            evidences.putIfAbsent(productId, ProductReviewEvidence.of(productId, avgRating, seeds.size(), mentions, null));
        }
        return evidences;
    }

    private static List<String[]> readRows(String path) {
        return read(path).lines()
                .skip(1)
                .filter(line -> !line.isBlank())
                .map(line -> line.split(",", -1))
                .toList();
    }

    private static List<ReviewSeed> readReviews() {
        return JsonMapper.builder().build().readValue(read("seed/reviews.json"), new TypeReference<>() {
        });
    }

    static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
