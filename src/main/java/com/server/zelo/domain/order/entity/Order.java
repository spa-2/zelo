package com.server.zelo.domain.order.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import com.server.zelo.domain.order.ActorType;
import com.server.zelo.domain.order.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "orders",
        indexes = @Index(name = "idx_orders_user_id_created_at", columnList = "user_id, created_at")
)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 견적 ID가 코드형식이니
    @Column(name = "quote_id", length = 100, nullable = false, unique = true)
    private String quoteId;

    @Column(name = "order_name", nullable = false)
    private String orderName;

    // 어떤 방식으로 주문을 요청했는가
    @Enumerated(EnumType.STRING)
    @Column(name = "requester_type", nullable = false)
    private ActorType requesterType;

    @Column(name = "chat_room_id", nullable = true)
    private Long chatRoomId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    private List<OrderItem> orderItems = new ArrayList<>();

    private Order(Long userId, String quoteId, String orderName,
                  ActorType requesterType, Long chatRoomId, Long totalAmount) {
        this.userId = userId;
        this.quoteId = quoteId;
        this.chatRoomId = chatRoomId;
        this.orderName = orderName;
        this.totalAmount = totalAmount;
        this.requesterType = requesterType;
        this.status = OrderStatus.PAYMENT_PENDING;
    }

    public void addOrderItem(OrderItem orderItem) {
        if (orderItem.getOrder() != this) {
            throw new IllegalArgumentException("주문 항목은 해당 주문에 속해야 합니다.");
        }
        this.orderItems.add(orderItem);
    }

    public static Order of(Long userId, String quoteId, String orderName,
                           ActorType requesterType, Long chatRoomId, Long totalAmount) {
        return new Order(userId, quoteId, orderName, requesterType, chatRoomId, totalAmount);
    }
}
