package com.server.zelo.domain.user.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true,nullable = false,length = 50)
    private String email;

    @Column(nullable = false,length = 255)
    private String password;

    @Column(nullable = false,length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String phoneNumber;

    private User(String email, String password, String name, String phoneNumber) {
        this.email = normalizeEmail(email);
        this.password = password;
        this.name = name;
        this.phoneNumber = phoneNumber;
    }

    public static User of(String email, String password, String name, String phoneNumber) {
        return new User(email, password, name, phoneNumber);
    }

    // 이메일 소문자로 변환
    public static String normalizeEmail(String email) {
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }
}
