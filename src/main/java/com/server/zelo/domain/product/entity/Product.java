package com.server.zelo.domain.product.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
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

    // 외부 노출용 상품 코드
    @Column(name = "product_code", nullable = false, length = 50, unique = true)
    private String productCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    // 같은 재료(김치)라도 브랜드별로 다른 상품이라 구분용으로 둠
    @Column(name = "brand", nullable = false, length = 50)
    private String brand;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", nullable = true, length = 500)
    private String description;

    @Column(name = "price", nullable = false)
    private Long price;

    // 상품 1개에 들어 있는 양 ex:상품명(사과1box) 갯수(15개) (재료의 base_unit 기준)
    @Column(name = "content_amount", nullable = false)
    private Integer contentAmount;

    // 사람이 읽는 판매 단위 ex: 1kg, 10구, 1단. 견적과 주문 상품(order_items.product_unit)에 그대로 복사한다
    @Column(name = "unit_label", nullable = false, length = 20)
    private String unitLabel;

    @Column(name = "sales_count", nullable = false)
    private Integer salesCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status;

    /**
     * 가격과 내용량을 검증하고 재료의 카테고리, 판매량 0, 판매 중 상태로 초기화한다.
     *
     * @throws BusinessException 가격이 없거나 음수인 경우, 내용량이 없거나 0 이하인 경우, 판매 단위가 비어 있거나 20자를 초과하는 경우 또는 브랜드가 비어 있거나 50자를 초과하는 경우
     */
    private Product(String productCode, Ingredient ingredient, String brand, String name,
                    String description, Long price, Integer contentAmount, String unitLabel) {
        if (price == null || price < 0) {
            throw new BusinessException(ErrorCode.INVALID_PRICE);
        }
        if (contentAmount == null || contentAmount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "상품 내용량은 0보다 커야 합니다.");
        }
        if (unitLabel == null || unitLabel.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "상품 판매 단위는 비어 있을 수 없습니다.");
        }
        if (unitLabel.length() > 20) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "상품 판매 단위는 20자를 초과할 수 없습니다.");
        }
        if (brand == null || brand.isBlank() || brand.length() > 50) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "상품 브랜드는 1~50자여야 합니다.");
        }
        this.productCode = productCode;
        // ERD 규칙: 상품 카테고리는 재료 카테고리와 같아야 하므로 입력받지 않고 재료에서 가져온다
        this.category = ingredient.getCategory();
        this.ingredient = ingredient;
        this.brand = brand;
        this.name = name;
        this.description = description;
        this.price = price;
        this.contentAmount = contentAmount;
        this.unitLabel = unitLabel;
        this.salesCount = 0;
        this.status = ProductStatus.ON_SALE;
    }

    /**
     * 재료의 카테고리를 따르는 상품을 판매량 0, 판매 중 상태로 생성한다.
     *
     * @param productCode 외부 노출용 상품 코드
     * @param ingredient 상품의 재료와 카테고리를 제공하는 재료
     * @param brand 브랜드 (1~50자)
     * @param name 상품 이름
     * @param description 상품 설명 (없으면 null)
     * @param price 0 이상의 가격
     * @param contentAmount 재료의 기본 단위 기준으로 0보다 큰 내용량
     * @param unitLabel 20자 이내의 사람이 읽는 판매 단위 (예: 1kg, 10구)
     * @return 생성한 상품
     * @throws BusinessException 가격이 없거나 음수이면 {@link ErrorCode#INVALID_PRICE},
     *                          내용량, 판매 단위, 브랜드가 잘못되면 {@link ErrorCode#INVALID_INPUT}으로 발생
     */
    public static Product of(String productCode, Ingredient ingredient, String brand, String name,
                             String description, Long price, Integer contentAmount, String unitLabel) {
        return new Product(productCode, ingredient, brand, name, description, price, contentAmount, unitLabel);
    }

    // 이미 발급된 견적은 주문할 때 가격 비교에서 PRICE_CHANGED로 걸림
    public void update(Long price, String description) {
        if (price == null || price < 0) {
            throw new BusinessException(ErrorCode.INVALID_PRICE);
        }
        this.price = price;
        this.description = description;
    }

    public void stopSale() {
        if (status == ProductStatus.STOP_SALE) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_STATUS);
        }
        this.status = ProductStatus.STOP_SALE;
    }

    public void resumeSale() {
        if (status == ProductStatus.ON_SALE) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_STATUS);
        }
        this.status = ProductStatus.ON_SALE;
    }
}
