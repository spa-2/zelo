package com.server.zelo.domain.user.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "user_addresses")
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

    // 삭제 전에는 값이 없으므로 nullable
    @Column(nullable = true)
    private LocalDateTime deleteAt;

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

    public static UserAddress of(User user, String place, String recipientName, String recipientPhone,
                                 String zipCode, String address, boolean isDefault) {
        return new UserAddress(user, place, recipientName, recipientPhone, zipCode, address, isDefault);
    }
}
