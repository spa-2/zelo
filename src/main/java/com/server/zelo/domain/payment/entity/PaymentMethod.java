package com.server.zelo.domain.payment.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 사용자가 등록한 카드·계좌. 카드번호·계좌번호는 저장하지 않고 포트원이 발급한 빌링키만 둔다
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "payment_methods",
        indexes = @Index(name = "idx_payment_methods_user_id", columnList = "user_id")
)
public class PaymentMethod extends BaseTimeEntity {

    private static final int BILLING_KEY_MAX_LENGTH = 200;
    private static final int DISPLAY_NAME_MAX_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 결제 도메인은 다른 도메인을 ID로만 들고 있다 (Payment.orderId, Refund.paymentId와 같은 방식)
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private PaymentMethodType type;

    @Column(name = "billing_key", nullable = false, unique = true, length = BILLING_KEY_MAX_LENGTH)
    private String billingKey;

    @Column(name = "display_name", nullable = false, length = DISPLAY_NAME_MAX_LENGTH)
    private String displayName;


    private PaymentMethod(Long userId, PaymentMethodType type, String billingKey, String displayName) {
        if (userId == null || type == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "사용자와 결제 수단 종류가 필요합니다.");
        }
        if (billingKey == null || billingKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "빌링키가 필요합니다.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "결제 수단 표시 이름이 필요합니다.");
        }
        // 컬럼 길이를 넘기면 저장할 때 DB 오류(500)가 나므로 미리 막는다
        if (billingKey.length() > BILLING_KEY_MAX_LENGTH || displayName.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "빌링키나 표시 이름이 너무 깁니다.");
        }
        this.userId = userId;
        this.type = type;
        this.billingKey = billingKey;
        this.displayName = displayName;
    }

    public static PaymentMethod of(Long userId, PaymentMethodType type, String billingKey, String displayName) {
        return new PaymentMethod(userId, type, billingKey, displayName);
    }
}