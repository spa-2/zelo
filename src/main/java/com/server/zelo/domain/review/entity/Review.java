package com.server.zelo.domain.review.entity;

import com.server.zelo.common.entity.BaseCreatedEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "product_reviews",
        indexes = @Index(name = "idx_product_reviews_product_id", columnList = "product_id")
)
public class Review extends BaseCreatedEntity {

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;
    private static final int CONTENT_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 리뷰는 시드로만 넣어 실제 회원이 없을 수 있으므로 회원과 FK로 묶지 않고 ID만 보관한다
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "rating", nullable = false, columnDefinition = "smallint")
    private Integer rating;

    @Column(name = "content", nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    private Review(Long userId, Product product, Integer rating, String content) {
        this.userId = userId;
        this.product = product;
        this.rating = rating;
        this.content = content;
    }

    public static Review of(Long userId, Product product, Integer rating, String content) {
        if (userId == null || product == null || !isValidRating(rating) || !isValidContent(content)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return new Review(userId, product, rating, content);
    }

    private static boolean isValidRating(Integer rating) {
        return rating != null && rating >= MIN_RATING && rating <= MAX_RATING;
    }

    private static boolean isValidContent(String content) {
        return StringUtils.hasText(content) && content.length() <= CONTENT_MAX_LENGTH;
    }
}
