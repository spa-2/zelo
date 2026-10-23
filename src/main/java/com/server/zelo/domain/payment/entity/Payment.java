package com.server.zelo.domain.payment.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "payments",
        indexes = {
                @Index(name = "idx_payments_order_id", columnList = "order_id"),
                @Index(name = "idx_payments_status_updated_at", columnList = "status, updated_at")
        }
)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 주문 도메인과 엔티티로 묶이지 않도록 ID만 보관한다. 결제 실패 후 재시도가 있어 unique가 아니다
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "portone_id", nullable = false, unique = true, length = 50)
    private String portoneId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    // 포트원 재조회로 확인한 실제 결제 금액. 결제 승인 전에는 null이다
    @Column(name = "pg_amount")
    private Long pgAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    private Payment(Long orderId, String portoneId, Long amount) {
        this.orderId = orderId;
        this.portoneId = portoneId;
        this.amount = amount;
        this.status = PaymentStatus.READY;
    }

    public static Payment of(Long orderId, String portoneId, Long amount) {
        return new Payment(orderId, portoneId, amount);   // 생성 시 status = READY
    }

    public void startConfirm() {
        changeStatus(PaymentStatus.CONFIRM_PENDING);
    }

    public void complete(Long pgAmount, LocalDateTime paidAt) {
        changeStatus(PaymentStatus.PAID);
        this.pgAmount = pgAmount;
        this.paidAt = paidAt;
    }

    public void fail() {
        changeStatus(PaymentStatus.FAILED);
    }

    // 포트원에서는 결제됐지만 금액이 달라 환불한 경우라 실제 결제 금액을 함께 남긴다
    public void cancel(Long pgAmount) {
        changeStatus(PaymentStatus.CANCELED);
        this.pgAmount = pgAmount;
    }

    public boolean isReady() {
        return status == PaymentStatus.READY;
    }

    // 포트원 확인이 끝나 결과가 반영된 결제
    public boolean isConfirmed() {
        return status == PaymentStatus.PAID || status == PaymentStatus.FAILED || status == PaymentStatus.CANCELED;
    }

    public boolean isAmountMatched(Long pgAmount) {
        return amount.equals(pgAmount);
    }

    private void changeStatus(PaymentStatus target) {
        if (!status.canTransitTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_STATUS);
        }
        this.status = target;
    }

    public void validateRefundable() {
        if (status != PaymentStatus.PAID) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_REFUNDABLE);
        }
    }
}