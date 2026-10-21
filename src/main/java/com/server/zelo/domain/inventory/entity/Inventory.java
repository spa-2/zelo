package com.server.zelo.domain.inventory.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "inventories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 상품별 재고 수량을 하나의 행에서 관리하므로 일대일로 매핑
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private Integer reservedQuantity;

    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity;

    @Version
    @Column(nullable = false)
    private Long version;

    private Inventory(Product product, Integer stockQuantity) {
        // 잘못된 재고 생성을 막기 위해 상품 누락과 실재고 수량의 null·음수를 검증
        if (product == null || stockQuantity == null || stockQuantity < 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "상품과 0 이상의 실재고 수량이 필요합니다."
            );
        }

        this.product = product;
        this.stockQuantity = stockQuantity;
        this.reservedQuantity = 0;
        this.availableQuantity = stockQuantity;
    }

    public static Inventory of(Product product, Integer stockQuantity) {
        return new Inventory(product, stockQuantity);
    }
}