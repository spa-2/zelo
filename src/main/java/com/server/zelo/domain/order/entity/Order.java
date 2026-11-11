package com.server.zelo.domain.order.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.user.entity.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "orders",
        indexes = {
                @Index(name = "idx_orders_user_id_created_at", columnList = "user_id, created_at"),
                @Index(name = "idx_orders_status_created_at", columnList = "status, created_at")
        }
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

    @Embedded
    private ShippingAddress shippingAddress;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    private List<OrderItem> orderItems = new ArrayList<>();

    public static final int PAYMENT_EXPIRATION_MINUTES = 30;

    private Order(
            User user,
            String quoteId,
            String orderName,
            ActorType requesterType,
            ChatRoom chatRoom,
            Long totalAmount,
            ShippingAddress shippingAddress
    ) {
        if (shippingAddress == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "주문 배송지가 필요합니다.");
        }

        this.user = user;
        this.quoteId = quoteId;
        this.chatRoom = chatRoom;
        this.orderName = orderName;
        this.totalAmount = totalAmount;
        this.requesterType = requesterType;
        this.status = OrderStatus.PAYMENT_PENDING;
        this.shippingAddress = shippingAddress;
    }

    /**
     * 현재 주문에 속한 상품을 주문 상품 목록에 추가한다.
     *
     * @param orderItem 추가할 주문 상품
     * @throws BusinessException 상품이 없거나 다른 주문에 속한 경우
     */
    public void addOrderItem(OrderItem orderItem) {
        // 연관관계의 주인인 OrderItem.order와 현재 주문의 목록이 일치하도록 검증한다.
        if (orderItem == null || orderItem.getOrder() != this) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "현재 주문에 속한 상품만 추가할 수 있습니다."
            );
        }

        this.orderItems.add(orderItem);
    }

    public static Order of(
            User user,
            String quoteId,
            String orderName,
            ActorType requesterType,
            ChatRoom chatRoom,
            Long totalAmount,
            ShippingAddress shippingAddress
    ) {
        return new Order(user, quoteId, orderName, requesterType, chatRoom, totalAmount, shippingAddress);
    }

    public void pay() {
        changeStatus(OrderStatus.PAID);
    }

    public void failPayment() {
        changeStatus(OrderStatus.PAYMENT_FAILED);
    }

    public void cancel() {
        changeStatus(OrderStatus.CANCELED);
    }

    // 허용하지 않은 상태 변경을 막아 주문 처리 순서를 지킴
    private void changeStatus(OrderStatus nextStatus) {
        if (!this.status.isTransitionAllowed(nextStatus)) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }

        this.status = nextStatus;
    }

    public boolean isFullyCanceled() {
        // 품목이 없는 주문을 전체 취소 완료로 판단하지 않음
        return !this.orderItems.isEmpty() && this.orderItems.stream().allMatch(OrderItem::isFullyCanceled);
    }

    public boolean isPaymentExpired(LocalDateTime now) {
        return !getCreatedAt().isAfter(now.minusMinutes(PAYMENT_EXPIRATION_MINUTES));
    }
}