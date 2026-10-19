package com.server.zelo.domain.order.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.order.ActorType;
import com.server.zelo.domain.order.OrderStatus;
import com.server.zelo.domain.user.entity.User;
import jakarta.persistence.CascadeType;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 견적 ID가 코드형식이니
    @Column(name = "quote_id", length = 100, nullable = false, unique = true)
    private String quoteId;

    @Column(name = "order_name", nullable = false)
    private String orderName;

    // 어떤 방식으로 주문을 요청했는가
    @Enumerated(EnumType.STRING)
    @Column(name = "requester_type", nullable = false)
    private ActorType requesterType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_room_id")
    private ChatRoom chatRoom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    private List<OrderItem> orderItems = new ArrayList<>();

    private Order(User user, String quoteId, String orderName,
                  ActorType requesterType, ChatRoom chatRoom, Long totalAmount) {
        this.user = user;
        this.quoteId = quoteId;
        this.chatRoom = chatRoom;
        this.orderName = orderName;
        this.totalAmount = totalAmount;
        this.requesterType = requesterType;
        this.status = OrderStatus.PAYMENT_PENDING;
    }

    public void addOrderItem(OrderItem orderItem) {
        this.orderItems.add(orderItem);
    }

    public static Order of(User user, String quoteId, String orderName,
                           ActorType requesterType, ChatRoom chatRoom, Long totalAmount) {
        return new Order(user, quoteId, orderName, requesterType, chatRoom, totalAmount);
    }
}