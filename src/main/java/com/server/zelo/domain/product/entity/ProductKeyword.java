package com.server.zelo.domain.product.entity;

import com.server.zelo.common.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "product_keywords",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_keywords", columnNames = {"product_id", "keyword"}),
        indexes = @Index(name = "idx_product_keywords_keyword", columnList = "keyword"))
public class ProductKeyword extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "keyword", nullable = false, length = 30)
    private Keyword keyword;

    /**
     * 키워드와 연결할 상품을 초기화한다.
     */
    private ProductKeyword(Product product, Keyword keyword) {
        this.product = product;
        this.keyword = keyword;
    }

    /**
     * 상품과 키워드의 연결을 생성한다.
     *
     * @param product 키워드를 연결할 상품
     * @param keyword 상품에 연결할 키워드
     * @return 생성한 상품 키워드 연결
     */
    public static ProductKeyword of(Product product, Keyword keyword) {
        return new ProductKeyword(product, keyword);
    }
}