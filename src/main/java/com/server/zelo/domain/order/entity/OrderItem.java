package com.server.zelo.domain.order.entity;

import com.server.zelo.common.entity.BaseCreatedEntity;
import com.server.zelo.domain.order.StockState;
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

import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "order_items",
        indexes = @Index(name = "idx_order_items_order_id", columnList = "order_id")
)
public class OrderItem extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

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

    @Column(name = "stock_state_changed_at", nullable = false)
    private LocalDateTime stockStateChangedAt;

    private OrderItem(Order order, Long productId, String productName,
                      String productUnit, Long unitPrice, Integer quantity,
                      Long lineAmount) {
        this.order = order;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lineAmount = lineAmount;
        this.productName = productName;
        this.productUnit = productUnit;
        this.stockState = StockState.RESERVED;
        this.stockStateChangedAt = LocalDateTime.now();
    }

    public static OrderItem of(Order order, Long productId, String productName,
                               String productUnit, Long unitPrice, Integer quantity,
                               Long lineAmount) {

        OrderItem orderItem = new OrderItem(order, productId, productName, productUnit, unitPrice, quantity, lineAmount);

        order.addOrderItem(orderItem);
        return orderItem;
    }
}
