package com.server.zelo.domain.refund.entity;
import com.server.zelo.common.entity.BaseCreatedEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.entity.OrderItem;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "refund_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_refund_items_refund_id_order_item_id",
                columnNames = {"refund_id", "order_item_id"}
        ),
        indexes = @Index(name = "idx_refund_items_order_item_id", columnList = "order_item_id")
)
public class RefundItem extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refund_id", nullable = false, updatable = false)
    private Refund refund;

    // 환불 수량을 주문 수량과 비교하고 주문 당시 단가를 가져와야 해서 주문 상품과 연결한다
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false, updatable = false)
    private OrderItem orderItem;

    @Column(name = "quantity", nullable = false, updatable = false)
    private Integer quantity;

    // 주문 당시 단가 스냅샷. 상품 가격이 바뀌어도 환불 금액은 주문 때 금액 기준이어야 한다
    @Column(name = "unit_price", nullable = false, updatable = false)
    private Long unitPrice;

    private RefundItem(OrderItem orderItem, Integer quantity) {
        if (orderItem == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "환불할 주문 상품이 필요합니다.");
        }
        if (quantity == null || quantity < 1 || quantity > orderItem.getQuantity()) {
            throw new BusinessException(ErrorCode.INVALID_REFUND_QUANTITY);
        }
        Long unitPrice = orderItem.getUnitPrice();
        if (unitPrice == null || unitPrice <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "주문 당시 단가는 0보다 커야 합니다.");
        }
        this.orderItem = orderItem;
        this.quantity = quantity;
        this.unitPrice = orderItem.getUnitPrice();
    }

    public static RefundItem of(OrderItem orderItem, Integer quantity) {
        return new RefundItem(orderItem, quantity);
    }

    public Long getAmount() {
        return unitPrice * quantity;
    }

    void assignRefund(Refund refund) {
        this.refund = refund;
    }
}