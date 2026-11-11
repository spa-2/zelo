package com.server.zelo.domain.user.entity;

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
@Table(
        name = "user_addresses",
        uniqueConstraints = @UniqueConstraint(
                // 같은 사용자는 같은 이름의 배송지를 두 개 가질 수 없다 ex) A 사용자의 "회사" 2개 방지
                name = "UK_user_address_place",
                columnNames = {"user_id", "place"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAddress extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JoinColumn(name = "user_id")
    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    @Column(nullable = false, length = 20)
    private String place;

    @Column(nullable = false, length = 50)
    private String recipientName;

    @Column(nullable = false, length = 20)
    private String recipientPhone;

    @Column(nullable = false, length = 10)
    private String zipCode;

    @Column(nullable = false, length = 200)
    private String address;

    @Column(nullable = false)
    private boolean isDefault;

    private UserAddress(User user, String place, String recipientName, String recipientPhone,
                        String zipCode, String address, boolean isDefault) {
        this.user = user;
        this.place = place;
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.zipCode = zipCode;
        this.address = address;
        this.isDefault = isDefault;
    }
    public void update(String place, String recipientName, String recipientPhone, String zipCode, String address) {
        this.place = place;
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.zipCode = zipCode;
        this.address = address;
    }

    // 기본 배송지가 없어지면 배송지를 정할 수 없으므로, 다른 배송지를 기본으로 지정하는 방식으로만 바꾼다
    public void validateKeepDefault(boolean isDefault) {
        if (this.isDefault && !isDefault) {
            throw new BusinessException(ErrorCode.DEFAULT_ADDRESS_REQUIRED);
        }
    }

    public static UserAddress of(User user, String place, String recipientName, String recipientPhone,
                                 String zipCode, String address, boolean isDefault) {
        return new UserAddress(user, place, recipientName, recipientPhone, zipCode, address, isDefault);
    }

    // 다른 배송지가 기본 배송지가 되면 기존 기본 배송지는 일반 배송지로 바뀐다
    public void releaseDefault() {
        this.isDefault = false;
    }

    public void validateDeletable() {
        if (this.isDefault) {
            throw new BusinessException(ErrorCode.DEFAULT_ADDRESS_REQUIRED);
        }
    }

    public void changeToDefault() {
        this.isDefault = true;
    }
}

