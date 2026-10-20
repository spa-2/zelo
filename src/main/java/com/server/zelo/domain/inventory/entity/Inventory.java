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

    /** 선점 작업 */
    public void reserve(Integer requestedQuantity) {

        validateQuantity(requestedQuantity);

        // 가용 재고가 부족하면 예외
        if (requestedQuantity > this.availableQuantity) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }

        this.reservedQuantity += requestedQuantity; // 선점 +q
        this.availableQuantity -= requestedQuantity; // 가용 −q
    }

    /** 차감 확정 */
    public void deduct(Integer requestedQuantity) {

        validateQuantity(requestedQuantity);

        // 선점 수량보다 많이 차감·해제되면 예외
        if (requestedQuantity > this.reservedQuantity) {
            throw new BusinessException(ErrorCode.INVALID_STOCK);
        }

        this.stockQuantity -= requestedQuantity; // 실재고 −q
        this.reservedQuantity -= requestedQuantity; // 선점 −q
    }

    /** 선점 해제 */
    public void release(Integer requestedQuantity) {

        validateQuantity(requestedQuantity);

        // 선점 수량보다 많이 차감·해제되면 예외
        if (requestedQuantity > this.reservedQuantity) {
            throw new BusinessException(ErrorCode.INVALID_STOCK);
        }

        this.reservedQuantity -= requestedQuantity; // 선점 −q
        this.availableQuantity += requestedQuantity; // 가용 +q
    }

    /** 재고 복원 */
    public void restore(Integer requestedQuantity) {

        validateQuantity(requestedQuantity);

        // 덧셈 과정에서 Integer 범위를 넘지 않도록 long으로 계산한다.
        long nextStockQuantity = (long) this.stockQuantity + requestedQuantity; // 실재고 +q
        long nextAvailableQuantity = (long) this.availableQuantity + requestedQuantity; // 가용 +q

        if (nextStockQuantity > Integer.MAX_VALUE
                || nextAvailableQuantity > Integer.MAX_VALUE) {
            throw new BusinessException(
                    ErrorCode.INVALID_STOCK,
                    "재고 수량이 허용 범위를 초과했습니다."
            );
        }

        this.stockQuantity = (int) nextStockQuantity;
        this.availableQuantity = (int) nextAvailableQuantity;
    }

    // 수량 1 이상인지 검증
    private void validateQuantity(Integer requestedQuantity) {
        if (requestedQuantity == null || requestedQuantity < 1) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        }
    }
}