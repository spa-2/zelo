package com.server.zelo.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.server.zelo.ai.tool.ReviewTools.CurrentQuoteItem;
import com.server.zelo.ai.tool.ReviewTools.RecentReview;
import com.server.zelo.ai.tool.ReviewTools.ReviewOverview;
import com.server.zelo.domain.product.dto.request.SearchProductCondition;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.service.QuoteService;
import com.server.zelo.domain.review.dto.response.ProductReviewEvidence;
import com.server.zelo.domain.review.entity.Review;
import com.server.zelo.domain.review.service.ReviewService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class ReviewToolsTest {

    private static final Long BAEK_ID = 1035L;
    private static final Long MANDU_ID = 1061L;

    @Mock
    private QuoteService quoteService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private ProductService productService;

    @InjectMocks
    private ReviewTools reviewTools;

    @Test
    void 상품_이름으로_찾은_상품마다_평점과_리뷰_수와_요약과_언급된_특징만_돌려준다() {
        // given
        Product baek = product(BAEK_ID, "브랜드 J", "백김치 1kg");
        Product mandu = product(MANDU_ID, "브랜드 G", "김치만두 1kg");
        given(productService.searchProducts(any(SearchProductCondition.class))).willReturn(new PageImpl<>(List.of(baek, mandu)));
        Map<Keyword, Integer> baekMentions = new LinkedHashMap<>();
        baekMentions.put(Keyword.NOT_SPICY, 15);
        baekMentions.put(Keyword.SPICY, 0);
        Map<Keyword, Integer> manduMentions = new LinkedHashMap<>();
        manduMentions.put(Keyword.NOT_SPICY, 0);
        given(reviewService.findReviewEvidences(List.of(BAEK_ID, MANDU_ID), EnumSet.allOf(Keyword.class))).willReturn(Map.of(
                BAEK_ID, ProductReviewEvidence.of(BAEK_ID, new BigDecimal("4.52"), 23, baekMentions, "맵지 않아요"),
                MANDU_ID, ProductReviewEvidence.of(MANDU_ID, null, 0, manduMentions, null)));

        // when
        List<ReviewOverview> overviews = reviewTools.getReviewOverview("김치");

        // then: 언급이 0인 특징은 빼고, 리뷰가 없는 상품도 0건으로 넣는다
        assertThat(overviews)
                .extracting(ReviewOverview::productId, ReviewOverview::brand, ReviewOverview::productName,
                        ReviewOverview::avgRating, ReviewOverview::reviewCount, ReviewOverview::summary, ReviewOverview::mentions)
                .containsExactly(
                        tuple(BAEK_ID, "브랜드 J", "백김치 1kg", new BigDecimal("4.52"), 23, "맵지 않아요", Map.of("맵지 않은", 15)),
                        tuple(MANDU_ID, "브랜드 G", "김치만두 1kg", null, 0, null, Map.of()));
    }

    @Test
    void 판매_중인_상품을_이름으로_최대_10개까지_찾는다() {
        // given
        given(productService.searchProducts(any(SearchProductCondition.class))).willReturn(new PageImpl<>(List.of()));
        ArgumentCaptor<SearchProductCondition> condition = ArgumentCaptor.forClass(SearchProductCondition.class);

        // when
        reviewTools.getReviewOverview("김치");

        // then
        then(productService).should().searchProducts(condition.capture());
        assertThat(condition.getValue().keyword()).isEqualTo("김치");
        assertThat(condition.getValue().page()).isZero();
        assertThat(condition.getValue().size()).isEqualTo(10);
    }

    @Test
    void 이름으로_찾은_상품이_없으면_리뷰를_조회하지_않고_빈_목록을_돌려준다() {
        // given
        given(productService.searchProducts(any(SearchProductCondition.class))).willReturn(new PageImpl<>(List.of()));

        // when
        List<ReviewOverview> overviews = reviewTools.getReviewOverview("없는상품");

        // then
        assertThat(overviews).isEmpty();
        then(reviewService).should(never()).findReviewEvidences(any(), any());
    }

    @Test
    void 최근_리뷰는_평점과_내용과_작성일만_돌려준다() {
        // given
        Review review = review(5, "하나도 안 매워요", LocalDateTime.of(2026, 10, 20, 9, 30));
        given(reviewService.findRecentReviews(BAEK_ID, 3)).willReturn(List.of(review));

        // when
        List<RecentReview> reviews = reviewTools.getRecentReviews(BAEK_ID, 3);

        // then
        assertThat(reviews).containsExactly(new RecentReview(5, "하나도 안 매워요", LocalDate.of(2026, 10, 20)));
    }

    @Test
    void 최근_리뷰_개수는_1에서_10_사이로_맞추고_말하지_않았으면_5개다() {
        // when
        reviewTools.getRecentReviews(BAEK_ID, 50);
        reviewTools.getRecentReviews(BAEK_ID, 0);
        reviewTools.getRecentReviews(BAEK_ID, null);

        // then
        then(reviewService).should().findRecentReviews(BAEK_ID, 10);
        then(reviewService).should().findRecentReviews(BAEK_ID, 1);
        then(reviewService).should().findRecentReviews(BAEK_ID, 5);
    }

    @Test
    void 서버가_넣은_방_번호의_견적에_담긴_상품을_돌려준다() {
        // given
        Quote quote = mock(Quote.class);
        given(quote.items()).willReturn(List.of(
                new QuoteItem(BAEK_ID, "SID-0008", "백김치 1kg", "1kg", 12_000L, 1, 12_000L),
                new QuoteItem(1039L, "SID-0020", "부침두부 300g", "300g", 2_500L, 2, 5_000L)));
        given(quoteService.findQuote(4701L)).willReturn(Optional.of(quote));

        // when
        List<CurrentQuoteItem> items = reviewTools.getCurrentQuoteItems(new ToolContext(Map.of(ReviewTools.ROOM_ID, 4701L)));

        // then
        assertThat(items).containsExactly(
                new CurrentQuoteItem(BAEK_ID, "백김치 1kg", "1kg", 1),
                new CurrentQuoteItem(1039L, "부침두부 300g", "300g", 2));
    }

    @Test
    void 견적이_없거나_만료됐으면_빈_목록을_돌려준다() {
        // given
        given(quoteService.findQuote(4701L)).willReturn(Optional.empty());

        // when
        List<CurrentQuoteItem> items = reviewTools.getCurrentQuoteItems(new ToolContext(Map.of(ReviewTools.ROOM_ID, 4701L)));

        // then
        assertThat(items).isEmpty();
    }

    private static Product product(Long id, String brand, String name) {
        Product product = mock(Product.class);
        given(product.getId()).willReturn(id);
        given(product.getBrand()).willReturn(brand);
        given(product.getName()).willReturn(name);
        return product;
    }

    private static Review review(Integer rating, String content, LocalDateTime createdAt) {
        Review review = mock(Review.class);
        given(review.getRating()).willReturn(rating);
        given(review.getContent()).willReturn(content);
        given(review.getCreatedAt()).willReturn(createdAt);
        return review;
    }
}
