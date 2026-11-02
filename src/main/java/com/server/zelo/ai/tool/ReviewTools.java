package com.server.zelo.ai.tool;

import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import com.server.zelo.domain.review.entity.Review;
import com.server.zelo.domain.review.service.ReviewService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

// 조회 질문에 답하는 LLM이 리뷰를 물을 때 부르는 조회 도구. 조회만 하므로 재시도로 두 번 불려도 결과가 같다.
// 상품(이름 검색)과 리뷰(집계·원문) 두 도메인을 엮으므로 각 Service를 부르기만 하고, 엔티티 대신 record로 돌려준다(open-in-view가 꺼져 있음)
@Component
@RequiredArgsConstructor
public class ReviewTools {

    // "김치"처럼 짧은 이름은 포기김치·백김치·김치만두가 함께 걸린다. 리뷰가 있는 상품이 판매량 순위에서 밀려 빠지지 않을 만큼 넉넉히 본다
    private static final int MAX_PRODUCTS = 10;
    private static final int MAX_RECENT_REVIEWS = 10;

    private final ReviewService reviewService;
    private final ProductService productService;

    @Tool(description = "상품 이름으로 판매 중인 상품을 찾아 상품별 평균 평점, 리뷰 수, 리뷰 한 줄 요약, 리뷰에서 언급된 특징별 횟수를 돌려준다. "
            + "평점이나 리뷰가 어떤지 물을 때 쓴다. reviewCount가 0이면 아직 리뷰가 없는 상품이다.")
    public List<ReviewOverview> getReviewOverview(
            @ToolParam(description = "찾을 상품 이름. 예: 백김치, 계란, 두부") String productName) {
        List<Product> products = productService.searchProducts(
                new SearchProductCondition(productName, null, false, null, 0, MAX_PRODUCTS)).getContent();
        if (products.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductReviewEvidence> evidences = reviewService.findReviewEvidences(
                products.stream().map(Product::getId).toList(), EnumSet.allOf(Keyword.class));
        return products.stream()
                .map(product -> ReviewOverview.from(product, evidences.get(product.getId())))
                .toList();
    }

    @Tool(description = "상품 하나의 최근 리뷰를 최신순으로 돌려준다. 리뷰 내용을 직접 보여 달라고 할 때 쓴다. "
            + "productId는 getReviewOverview 결과의 productId를 쓴다.")
    public List<RecentReview> getRecentReviews(
            @ToolParam(description = "getReviewOverview에서 받은 상품 ID") Long productId,
            @ToolParam(description = "가져올 리뷰 수. 1~10, 말하지 않았으면 5") Integer count) {
        int limit = Math.clamp(count == null ? 5 : count, 1, MAX_RECENT_REVIEWS);
        return reviewService.findRecentReviews(productId, limit).stream()
                .map(RecentReview::from)
                .toList();
    }

    public record ReviewOverview(
            Long productId,
            String brand,
            String productName,
            // 리뷰가 없으면 null
            BigDecimal avgRating,
            Integer reviewCount,
            // 리뷰가 적거나 아직 만들지 않았으면 null
            String summary,
            // 리뷰에서 언급된 특징("매콤한")과 횟수. 언급이 없는 특징은 뺀다
            Map<String, Integer> mentions
    ) {

        public static ReviewOverview from(Product product, ProductReviewEvidence evidence) {
            Map<String, Integer> mentions = new LinkedHashMap<>();
            evidence.keywordMentions().forEach((keyword, count) -> {
                if (count > 0) {
                    mentions.put(keyword.getLabel(), count);
                }
            });
            return new ReviewOverview(product.getId(), product.getBrand(), product.getName(), evidence.avgRating(),
                    evidence.reviewCount(), evidence.summary(), mentions);
        }
    }

    // 작성자 ID는 넣지 않는다
    public record RecentReview(
            Integer rating,
            String content,
            LocalDate createdDate
    ) {

        public static RecentReview from(Review review) {
            return new RecentReview(review.getRating(), review.getContent(), review.getCreatedAt().toLocalDate());
        }
    }
}
