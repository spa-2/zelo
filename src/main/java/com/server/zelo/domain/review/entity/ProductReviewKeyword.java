package com.server.zelo.domain.review.entity;

import com.server.zelo.common.entity.BaseUpdatedEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 상품의 리뷰 중 해당 키워드를 언급한 리뷰 수. 상품 라벨(product_keywords)과 달리 리뷰를 센 결과다
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "product_review_keywords",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_review_keywords", columnNames = {"product_id", "keyword"})
)
public class ProductReviewKeyword extends BaseUpdatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "keyword", nullable = false, length = 30)
    private Keyword keyword;

    @Column(name = "mention_count", nullable = false)
    private Integer mentionCount;

    private ProductReviewKeyword(Product product, Keyword keyword, Integer mentionCount) {
        this.product = product;
        this.keyword = keyword;
        this.mentionCount = mentionCount;
    }

    public static ProductReviewKeyword of(Product product, Keyword keyword, Integer mentionCount) {
        if (product == null || keyword == null || mentionCount == null || mentionCount < 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return new ProductReviewKeyword(product, keyword, mentionCount);
    }
}
