package com.server.zelo.domain.order.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.Product;
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
@Table(
        name = "order_items",
        indexes = @Index(name = "idx_order_items_order_id", columnList = "order_id")
)
public class OrderItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "product_unit", length = 100, nullable = false)
    private String productUnit;

    @Column(name = "unit_price", nullable = false)
    private Long unitPrice;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "line_amount", nullable = false)
    private Long lineAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_state", nullable = false)
    private StockState stockState;

    // 구매 수량은 유지하고 취소한 수량을 별도로 누적
    @Column(name = "canceled_quantity", nullable = false)
    @ColumnDefault("0")
    private Integer canceledQuantity;

    private OrderItem(Order order, Product product, String productName,
                      String productUnit, Long unitPrice, Integer quantity,
                      Long lineAmount) {
        this.order = order;
        this.product = product;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lineAmount = lineAmount;
        this.productName = productName;
        this.productUnit = productUnit;
        this.stockState = StockState.RESERVED;
        this.canceledQuantity = 0;
    }

    public static OrderItem of(Order order, Product product, String productName,
                               String productUnit, Long unitPrice, Integer quantity,
                               Long lineAmount) {

        OrderItem orderItem = new OrderItem(order, product, productName, productUnit, unitPrice, quantity, lineAmount);

        order.addOrderItem(orderItem);
        return orderItem;
    }

    // 차감
    public void deduct() {
        changeStockState(StockState.DEDUCTED);
    }

    // 해제
    public void release() {
        changeStockState(StockState.RELEASED);
    }

    // 복원
    public void restore() {
        // 전체 복원도 이미 취소된 수량을 제외하고 취소 이력에 반영
        cancelQuantity(getCancelableQuantity());
    }

    public boolean isReserved() {
        return this.stockState == StockState.RESERVED;
    }

    // 허용하지 않은 상태 변경을 막아 재고 처리 순서를 지킴
    private void changeStockState(StockState nextStatus) {
        if (!this.stockState.isTransitionAllowed(nextStatus)) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_STATUS);
        }

        this.stockState = nextStatus;
    }

    public Integer getCancelableQuantity() {
        return this.quantity - this.canceledQuantity;
    }

    public boolean isFullyCanceled() {
        return this.quantity.equals(this.canceledQuantity);
    }

    public void validateCancelQuantity(Integer requestedQuantity) {
        // 판매가 확정된 품목만 환불에 따른 수량 취소 가능
        if (this.stockState != StockState.DEDUCTED) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_STATUS);
        }

        // 이전에 취소한 수량을 제외하여 구매 수량보다 많이 복원되는 것을 막음
        if (requestedQuantity == null || requestedQuantity < 1 || requestedQuantity > getCancelableQuantity()) {
            throw new BusinessException(ErrorCode.INVALID_REFUND_QUANTITY);
        }
    }

    public void cancelQuantity(Integer requestedQuantity) {
        validateCancelQuantity(requestedQuantity);

        this.canceledQuantity += requestedQuantity;

        // 일부만 취소됐다면 DEDUCTED를 유지하고, 전량 취소됐을 때만 RESTORED로 변경
        if (isFullyCanceled()) {
            changeStockState(StockState.RESTORED);
        }
    }
}
