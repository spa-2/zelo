package com.server.zelo.product.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "products", indexes = {
        @Index(name = "idx_products_ingredient_status", columnList = "ingredient_id, status"),
        @Index(name = "idx_products_category_price", columnList = "category_id, price"),
        @Index(name = "idx_products_category_sales", columnList = "category_id, sales_count")
})
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 외부 노출용 상품 코드 (SKU)
    @Column(name = "product_code", nullable = false, length = 50, unique = true)
    private String productCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", nullable = true, length = 500)
    private String description;

    @Column(name = "price", nullable = false)
    private Integer price;

    // 상품 1개에 들어 있는 양 ex:상품명(사과1box) 갯수(15개) (재료의 base_unit 기준)
    @Column(name = "content_amount", nullable = false)
    private Integer contentAmount;

    @Column(name = "sales_count", nullable = false)
    private Integer salesCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status;

    private Product(String productCode, Ingredient ingredient, String name,
                    String description, Integer price, Integer contentAmount) {
        this.productCode = productCode;
        // ERD 규칙: 상품 카테고리는 재료 카테고리와 같아야 하므로 입력받지 않고 재료에서 가져온다
        this.category = ingredient.getCategory();
        this.ingredient = ingredient;
        this.name = name;
        this.description = description;
        this.price = price;
        this.contentAmount = contentAmount;
        this.salesCount = 0;
        this.status = ProductStatus.ON_SALE;
    }

    public static Product of(String productCode, Ingredient ingredient, String name,
                             String description, Integer price, Integer contentAmount) {
        return new Product(productCode, ingredient, name, description, price, contentAmount);
    }
}