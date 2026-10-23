package com.server.zelo.global.seed;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.repository.ProductRepository;
import com.server.zelo.domain.review.entity.ProductReviewKeyword;
import com.server.zelo.domain.review.entity.ProductReviewSummary;
import com.server.zelo.domain.review.entity.Review;
import com.server.zelo.domain.review.repository.ProductReviewKeywordRepository;
import com.server.zelo.domain.review.repository.ProductReviewSummaryRepository;
import com.server.zelo.domain.review.repository.ReviewRepository;
import com.server.zelo.domain.review.service.ReviewCommandService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// 로컬 시연용 리뷰 데이터. 상품 시드와 같이 ZELO_SEED_ENABLED=true 로 켰을 때만 돌고, 리뷰가 이미 있으면 건너뜀
// 리뷰는 상품에 붙으므로, 상품 시드(ApplicationRunner)가 모두 끝난 뒤 오는 ApplicationReadyEvent에서 넣는다
// 리뷰 내용에 쉼표가 들어가서 상품 시드의 CSV 대신 JSON으로 읽는다
@Slf4j
@Component
@ConditionalOnProperty(name = "zelo.seed.enabled", havingValue = "true")
public class ReviewSeedLoader {

    private static final String REVIEWS_JSON = "seed/reviews.json";
    // 리뷰는 회원과 FK로 묶지 않아서, 실제 회원 ID와 섞이지 않을 번호대부터 작성자 ID를 붙인다
    private static final long SEED_USER_ID_START = 9_100_000L;
    private static final String REAL_LLM_MODE = "real";
    private static final int AVG_RATING_SCALE = 2;

    private final String llmMode;
    private final JsonMapper jsonMapper;
    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final TransactionTemplate transactionTemplate;
    private final ReviewCommandService reviewCommandService;
    private final ProductReviewKeywordRepository productReviewKeywordRepository;
    private final ProductReviewSummaryRepository productReviewSummaryRepository;

    public ReviewSeedLoader(
            @Value("${zelo.llm.mode}") String llmMode,
            JsonMapper jsonMapper,
            ReviewRepository reviewRepository,
            ProductRepository productRepository,
            TransactionTemplate transactionTemplate,
            ReviewCommandService reviewCommandService,
            ProductReviewKeywordRepository productReviewKeywordRepository,
            ProductReviewSummaryRepository productReviewSummaryRepository) {
        this.llmMode = llmMode;
        this.jsonMapper = jsonMapper;
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.transactionTemplate = transactionTemplate;
        this.reviewCommandService = reviewCommandService;
        this.productReviewKeywordRepository = productReviewKeywordRepository;
        this.productReviewSummaryRepository = productReviewSummaryRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        if (reviewRepository.count() > 0) {
            log.info("리뷰 시드가 이미 있어서 건너뜀");
            return;
        }
        List<ReviewSeed> seeds = readSeeds();
        Integer reviewCount = transactionTemplate.execute(status -> saveReviews(seeds));
        log.info("리뷰 시드 저장 완료 리뷰={}", reviewCount);

        // 요약은 리뷰를 처음 넣을 때 한 번만 만든다. LLM을 부르므로 저장이 커밋된 뒤 트랜잭션 밖에서 하고,
        // fake 모드의 고정 문구가 실제 요약처럼 DB에 남지 않도록 real 모드에서만 만든다
        if (REAL_LLM_MODE.equals(llmMode)) {
            reviewCommandService.createSummaries();
        } else {
            log.info("LLM이 real 모드가 아니라 리뷰 요약은 만들지 않음 mode={}", llmMode);
        }
    }

    // 리뷰를 저장하면서 상품별 평균 평점·리뷰 수와 키워드 언급 수를 함께 세서 집계 테이블을 채운다
    private int saveReviews(List<ReviewSeed> seeds) {
        // 시드 상품은 수백 개라 한 번에 읽어 코드로 찾는다
        Map<String, Product> products = productRepository.findAll().stream()
                .collect(Collectors.toMap(Product::getProductCode, Function.identity()));
        List<Review> reviews = new ArrayList<>();
        Map<Product, List<ReviewSeed>> seedsByProduct = new LinkedHashMap<>();
        long userId = SEED_USER_ID_START;
        for (ReviewSeed seed : seeds) {
            Product product = findProduct(products, seed.productCode());
            reviews.add(Review.of(userId++, product, seed.rating(), seed.content()));
            seedsByProduct.computeIfAbsent(product, key -> new ArrayList<>()).add(seed);
        }
        reviewRepository.saveAll(reviews);

        List<ProductReviewSummary> summaries = new ArrayList<>();
        List<ProductReviewKeyword> productReviewKeywords = new ArrayList<>();
        seedsByProduct.forEach((product, productSeeds) -> {
            summaries.add(ProductReviewSummary.of(product, averageRating(productSeeds), productSeeds.size()));
            countMentions(productSeeds).forEach((keyword, count) ->
                    productReviewKeywords.add(ProductReviewKeyword.of(product, keyword, count)));
        });
        productReviewSummaryRepository.saveAll(summaries);
        productReviewKeywordRepository.saveAll(productReviewKeywords);
        return reviews.size();
    }

    private static BigDecimal averageRating(List<ReviewSeed> seeds) {
        int sum = seeds.stream().mapToInt(ReviewSeed::rating).sum();
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(seeds.size()), AVG_RATING_SCALE, RoundingMode.HALF_UP);
    }

    // 언급된 키워드만 센다. 언급이 없는 키워드는 행을 만들지 않고, 조회할 때 0으로 채운다
    private static Map<Keyword, Integer> countMentions(List<ReviewSeed> seeds) {
        Map<Keyword, Integer> counts = new EnumMap<>(Keyword.class);
        seeds.forEach(seed -> seed.keywords().forEach(keyword -> counts.merge(keyword, 1, Integer::sum)));
        return counts;
    }

    // 시드 파일의 상품 코드 오타는 어디가 틀렸는지 바로 보이게 시작을 멈춘다
    private static Product findProduct(Map<String, Product> products, String productCode) {
        Product product = products.get(productCode);
        if (product == null) {
            throw new IllegalStateException(REVIEWS_JSON + "에서 찾을 수 없는 상품 코드: " + productCode);
        }
        return product;
    }

    private List<ReviewSeed> readSeeds() {
        try {
            String content = new ClassPathResource(REVIEWS_JSON).getContentAsString(StandardCharsets.UTF_8);
            return jsonMapper.readValue(content, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // reviews.json 한 줄. 키워드는 이 리뷰가 언급한 조건(Keyword) 태그다
    record ReviewSeed(String productCode, Integer rating, String content, List<Keyword> keywords) {
    }
}
