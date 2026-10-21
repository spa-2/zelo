package com.server.zelo.domain.refund.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import jakarta.persistence.*;
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
        name = "refunds",
        indexes = @Index(name = "idx_refunds_payment_id", columnList = "payment_id")
)
public class Refund extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "reason", nullable = false, length = 50)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_type", nullable = false, length = 20)
    private RefundType refundType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "items_amount", nullable = false)
    private Long itemsAmount;

    // 포트원에서 환불이 완료된 시각. 완료 전에는 null이다
    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @OneToMany(mappedBy = "refund", cascade = CascadeType.PERSIST)
    private List<RefundItem> refundItems = new ArrayList<>();

    private Refund(Long paymentId, RefundType refundType, String reason) {
        if (paymentId == null || refundType == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "환불할 결제와 환불 유형이 필요합니다.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "환불 사유가 필요합니다.");
        }
        this.paymentId = paymentId;
        this.refundType = refundType;
        this.reason = reason;
        this.status = RefundStatus.REQUESTED;
        this.itemsAmount = 0L;
    }

    public static Refund of(Long paymentId, RefundType refundType, String reason) {
        return new Refund(paymentId, refundType, reason);
    }

    public void addRefundItem(RefundItem refundItem) {
        if (refundItem == null || refundItem.getRefund() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "다른 환불에 담기지 않은 환불 상품만 담을 수 있습니다.");
        }
        if (status != RefundStatus.REQUESTED) {
            throw new BusinessException(ErrorCode.INVALID_REFUND_STATUS);
        }

        boolean duplicated = refundItems.stream()
                .anyMatch(item -> item.getOrderItem() == refundItem.getOrderItem());
        if (duplicated) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "한 환불에 같은 주문 상품을 두 번 담을 수 없습니다.");
        }

        refundItem.assignRefund(this);
        this.refundItems.add(refundItem);
        this.itemsAmount += refundItem.getAmount();
    }

    public void complete(LocalDateTime refundedAt) {
        validateTime(refundedAt);
        changeStatus(RefundStatus.COMPLETED);
        this.refundedAt = refundedAt;
    }

    public void fail() {
        changeStatus(RefundStatus.FAILED);
    }

    // 같은 결제에 진행 중인 환불이 있으면 새 환불 요청을 막는다 (E-2)
    public boolean isInProgress() {
        return status == RefundStatus.REQUESTED;
    }

    private void changeStatus(RefundStatus target) {
        if (!status.canTransitTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_REFUND_STATUS);
        }
        this.status = target;
    }

    private void validateTime(LocalDateTime time) {
        if (time == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "상태 변경 시각이 필요합니다.");
        }
    }
}