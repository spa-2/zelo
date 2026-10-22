package com.server.zelo.domain.review.entity;

import com.server.zelo.common.entity.BaseUpdatedEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_review_summaries")
public class ProductReviewSummary extends BaseUpdatedEntity {

    private static final BigDecimal MIN_AVG_RATING = BigDecimal.ONE;
    private static final BigDecimal MAX_AVG_RATING = BigDecimal.valueOf(5);
    private static final int AVG_RATING_SCALE = 2;

    // 상품마다 요약은 한 행뿐이라 상품 ID를 그대로 PK로 쓴다
    @Id
    @Column(name = "product_id")
    private Long productId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "avg_rating", nullable = false, precision = 3, scale = AVG_RATING_SCALE)
    private BigDecimal avgRating;

    // 리뷰가 없는 상품은 요약 행을 만들지 않으므로 항상 1 이상이다
    @Column(name = "review_count", nullable = false)
    private Integer reviewCount;

    // 리뷰 원문을 AI가 한 줄로 줄인 문장. 요약을 만들기 전에는 null이다
    @Column(name = "content", nullable = true, length = 200)
    private String content;

    private ProductReviewSummary(Product product, BigDecimal avgRating, Integer reviewCount) {
        this.product = product;
        this.avgRating = avgRating;
        this.reviewCount = reviewCount;
    }

    public static ProductReviewSummary of(Product product, BigDecimal avgRating, Integer reviewCount) {
        if (product == null || !isValidAvgRating(avgRating) || reviewCount == null || reviewCount < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        // DB 컬럼(decimal(3,2))과 같은 자릿수로 맞춰, 저장 전후 값이 달라 보이지 않게 한다
        return new ProductReviewSummary(product, avgRating.setScale(AVG_RATING_SCALE, RoundingMode.HALF_UP), reviewCount);
    }

    private static boolean isValidAvgRating(BigDecimal avgRating) {
        return avgRating != null
                && avgRating.compareTo(MIN_AVG_RATING) >= 0
                && avgRating.compareTo(MAX_AVG_RATING) <= 0;
    }
}
