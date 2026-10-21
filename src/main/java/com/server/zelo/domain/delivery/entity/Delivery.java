package com.server.zelo.domain.delivery.entity;

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
        name = "deliveries",
        indexes = @Index(name = "idx_deliveries_status_updated_at", columnList = "status, updated_at")
)
public class Delivery extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 주문 도메인과 엔티티로 묶이지 않도록 ID만 보관한다. 주문 1건에 배송 1건이라 unique다
    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    // 주소록(user_address)을 참조하지 않고 주문 시점 값을 복사한다. 주소록이 바뀌어도 배송지는 그대로여야 하기 때문
    @Column(name = "recipient_name", nullable = false, length = 50)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    @Column(name = "zip_code", nullable = false, length = 10)
    private String zipCode;

    @Column(name = "address", nullable = false, length = 200)
    private String address;

    @Column(name = "address_detail", length = 200)
    private String addressDetail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    private Delivery(Long orderId, String recipientName, String recipientPhone,
                     String zipCode, String address, String addressDetail) {
        if (orderId == null || isBlank(recipientName) || isBlank(recipientPhone)
                || isBlank(zipCode) || isBlank(address)) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "주문과 받는 사람, 연락처, 우편번호, 주소가 필요합니다."
            );
        }
        this.orderId = orderId;
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.zipCode = zipCode;
        this.address = address;
        this.addressDetail = addressDetail;
        this.status = DeliveryStatus.READY;
    }

    public static Delivery of(Long orderId, String recipientName, String recipientPhone,
                              String zipCode, String address, String addressDetail) {
        return new Delivery(orderId, recipientName, recipientPhone, zipCode, address, addressDetail);
    }

    public void ship(LocalDateTime shippedAt) {
        changeStatus(DeliveryStatus.SHIPPING);
        this.shippedAt = shippedAt;
    }

    public void complete(LocalDateTime deliveredAt) {
        changeStatus(DeliveryStatus.DELIVERED);
        this.deliveredAt = deliveredAt;
    }

    public void cancel(LocalDateTime canceledAt) {
        changeStatus(DeliveryStatus.CANCELED);
        this.canceledAt = canceledAt;
    }

    // 출고 전이어야 환불할 수 있다
    public boolean isCancelable() {
        return status == DeliveryStatus.READY;
    }

    private void changeStatus(DeliveryStatus target) {
        if (!status.canTransitTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_DELIVERY_STATUS);
        }
        this.status = target;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}