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

    // 소셜 회원가입 사용자는 패스워드가 존재하지 않음
    @Column(nullable = true,length = 255)
    private String password;

    @Column(nullable = false,length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String phoneNumber;

    // 회원가입 시 기본 역할인 Basic이고 Admin은 시드(AdminSeedLoader)로 넣는다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false,length = 20)
    private UserRole role;

    private User(String email, String password, String name, String phoneNumber) {
        this.email = normalizeEmail(email);
        this.password = password;
        this.name = name;
        this.phoneNumber = phoneNumber;
        role = UserRole.BASIC;
    }

    public static User of(String email, String password, String name, String phoneNumber) {
        return new User(email, password, name, phoneNumber);
    }

    public static User ofSocial(String email, String name, String phoneNumber) {
        return new User(email, null, name, phoneNumber);
    }

    // 관리자는 가입 API가 없어서 시드로만 만든다
    public static User ofAdmin(String email, String password, String name, String phoneNumber) {
        User user = new User(email, password, name, phoneNumber);
        user.role = UserRole.ADMIN;
        return user;
    }

    public boolean isPasswordNull(){
        return password == null;
    }

    // 이메일 소문자로 변환
    public static String normalizeEmail(String email) {
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }
}
