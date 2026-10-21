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

/**
 * 🌼재고 필드🌼
 *
 * <ul>
 *   <li>{@code stockQuantity}: 실재고. 선점 수량을 포함한 전체 재고 수량</li>
 *   <li>{@code reservedQuantity}: 선점 재고. 결제 대기 주문에 확보해 둔 수량</li>
 *   <li>{@code availableQuantity}: 가용 재고. 추가 주문이 가능한 수량. 실재고 - 선점</li>
 *   <li>{@code version}: 낙관적 락 충돌 검사에 사용하는 버전</li>
 * </ul>
 */
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

    /**
     * 요청 수량만큼 재고를 선점한다.
     *
     * @param requestedQuantity 이번 주문 항목에서 선점할 수량.
     *                          재고 필드는 상품 전체 합계이므로 처리할 수량을 별도로 전달받는다.
     */
    public void reserve(Integer requestedQuantity) {

        validateQuantity(requestedQuantity);

        // 가용 재고가 부족하면 예외
        if (requestedQuantity > this.availableQuantity) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }

        this.reservedQuantity += requestedQuantity; // 선점 +q
        this.availableQuantity = this.stockQuantity - this.reservedQuantity; // 가용 −q
    }

    /**
     * 결제가 확정된 수량만큼 실재고와 선점 수량을 차감한다.
     * 두 수량이 함께 감소하므로 가용 수량은 유지된다.
     *
     * @param requestedQuantity 이번 주문 항목에서 차감 확정할 수량
     * @throws BusinessException 요청 수량이 null·0·음수이거나 선점 수량을 초과한 경우
     */
    public void deduct(Integer requestedQuantity) {

        validateQuantity(requestedQuantity);

        // 선점 수량보다 많이 차감·해제되면 예외
        if (requestedQuantity > this.reservedQuantity) {
            throw new BusinessException(ErrorCode.INVALID_STOCK);
        }

        this.stockQuantity -= requestedQuantity; // 실재고 −q
        this.reservedQuantity -= requestedQuantity; // 선점 −q
        // 실재고와 선점이 같은 수량만큼 줄어 가용 수량은 유지된다.
    }

    /**
     * 결제 전 취소·만료 또는 결제 실패가 확정된 주문의 선점을 해제한다.
     * 실재고는 유지하고, 해제한 수량만큼 가용 수량을 늘린다.
     *
     * @param requestedQuantity 이번 주문 항목에서 선점을 해제할 수량
     * @throws BusinessException 요청 수량이 null·0·음수이거나 선점 수량을 초과한 경우
     */
    public void release(Integer requestedQuantity) {

        validateQuantity(requestedQuantity);

        // 선점 수량보다 많이 차감·해제되면 예외
        if (requestedQuantity > this.reservedQuantity) {
            throw new BusinessException(ErrorCode.INVALID_STOCK);
        }

        this.reservedQuantity -= requestedQuantity; // 선점 −q
        this.availableQuantity = this.stockQuantity - this.reservedQuantity; // 가용 +q
    }

    /**
     * 이미 차감한 수량을 실재고와 가용 수량에 복원한다.
     * 선점 수량은 유지하며, 정수 범위 초과 여부를 확인한 뒤 수량을 변경한다.
     * 복원 가능 여부와 중복 처리 여부는 호출하는 주문·환불 흐름에서 확인한다.
     *
     * @param requestedQuantity 이번 주문 항목에서 복원할 수량
     * @throws BusinessException 요청 수량이 null·0·음수이거나 복원 후 실재고가 Integer 범위를 초과한 경우
     */
    public void restore(Integer requestedQuantity) {

        validateQuantity(requestedQuantity);

        // 덧셈 과정에서 Integer 범위를 넘지 않도록 long으로 계산한다.
        long nextStockQuantity = (long) this.stockQuantity + requestedQuantity; // 실재고 +q

        if (nextStockQuantity > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_STOCK, "재고 수량이 허용 범위를 초과했습니다.");
        }

        this.stockQuantity = (int) nextStockQuantity;
        this.availableQuantity = this.stockQuantity - this.reservedQuantity; // 가용 +q
    }

    // 수량 1 이상인지 검증
    private void validateQuantity(Integer requestedQuantity) {
        if (requestedQuantity == null || requestedQuantity < 1) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        }
    }
}