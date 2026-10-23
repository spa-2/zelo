package com.server.zelo.domain.order.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShippingAddress {

    @Column(name = "recipient_name", nullable = false, length = 50)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    @Column(name = "zip_code", nullable = false, length = 10)
    private String zipCode;

    @Column(name = "address", nullable = false, length = 200)
    private String address;

    private ShippingAddress(String recipientName, String recipientPhone, String zipCode, String address) {
        // 배송 필수 정보가 누락된 스냅샷이 생성되지 않도록 저장 전에 검증
        if (recipientName == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "받는 사람 이름이 필요합니다.");
        }

        if (recipientPhone == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "받는 사람 연락처가 필요합니다.");
        }

        if (zipCode == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "우편번호가 필요합니다.");
        }

        if (address == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "주소가 필요합니다.");
        }

        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.zipCode = zipCode;
        this.address = address;
    }

    public static ShippingAddress of(String recipientName, String recipientPhone, String zipCode, String address) {
        return new ShippingAddress(recipientName, recipientPhone, zipCode, address);
    }
}