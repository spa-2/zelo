package com.server.zelo.domain.order.entity;

import com.server.zelo.common.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 추가만 하는 주문 이력. 합계는 orders.total_amount와 조인해서 본다
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "order_audit_logs",
        indexes = {
                @Index(name = "ix_order_audit_order_created", columnList = "order_id, created_at"),
                @Index(name = "ix_order_audit_user_created", columnList = "user_id, created_at"),
                @Index(name = "ix_order_audit_action_created", columnList = "action, created_at")
        }
)
public class OrderAuditLog extends BaseCreatedEntity {

    private static final int REASON_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 주문이 생성되지 않은 거부 요청은 null
    @Column(name = "order_id", updatable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "quote_id", length = 100, updatable = false)
    private String quoteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private OrderAuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false)
    private ActorType actorType;

    // USER는 회원 ID, SYSTEM은 null
    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    // 생성 기록과 거부 요청은 null
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", updatable = false)
    private OrderStatus previousStatus;

    // 거부 요청은 null
    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", updatable = false)
    private OrderStatus newStatus;

    @Column(name = "error_code", length = 50, updatable = false)
    private String errorCode;

    @Column(length = REASON_MAX_LENGTH, updatable = false)
    private String reason;

    private OrderAuditLog(Long orderId, Long userId, String quoteId, OrderAuditAction action,
                          ActorType actorType, Long actorId, OrderStatus previousStatus,
                          OrderStatus newStatus, String errorCode, String reason) {
        this.orderId = orderId;
        this.userId = userId;
        this.quoteId = quoteId;
        this.action = action;
        this.actorType = actorType;
        this.actorId = actorId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.errorCode = errorCode;
        this.reason = truncate(reason);
    }

    // 주문 생성이나 상태 변경 기록. 바뀐 뒤의 상태는 주문에서 읽는다
    public static OrderAuditLog stateChanged(Order order, OrderAuditAction action, OrderStatus previousStatus,
                                             ActorType actorType, Long actorId, String reason) {
        return new OrderAuditLog(order.getId(), order.getUser().getId(), order.getQuoteId(), action,
                actorType, actorId, previousStatus, order.getStatus(), null, reason);
    }

    // 사용자가 보낸 주문 요청이 거부된 기록
    public static OrderAuditLog rejected(Long userId, String quoteId, String errorCode, String reason) {
        return new OrderAuditLog(null, userId, quoteId, OrderAuditAction.REQUEST_REJECTED,
                ActorType.USER, userId, null, null, errorCode, reason);
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= REASON_MAX_LENGTH) {
            return reason;
        }
        return reason.substring(0, REASON_MAX_LENGTH);
    }
}
