package com.server.zelo.domain.user.entity;

import com.server.zelo.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "social_accounts",
        uniqueConstraints = {
                // 같은 소셜 계정의 중복 생성 방지 ex) 동일한 구글A 계정 2개 생성 방지
                @UniqueConstraint(
                        name = "UK_same_social_account",
                        columnNames = {"provider", "provider_id"}
                ),
                // 한 user가 같은 provider 중복 연결 방지 ex) A 사용자가 카카오를 통해 계정 2개 생성 방지
                @UniqueConstraint(
                        name = "UK_same_provider_user",
                        columnNames = {"user_id", "provider"}
                )
        }
)
public class SocialAccount extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SocialProvider provider;

    @Column(nullable = false, length = 255, name = "provider_id")
    private String providerId;

    private SocialAccount(User user, SocialProvider provider, String providerId) {
        this.user = user;
        this.provider = provider;
        this.providerId = providerId;
    }

    public static SocialAccount of(User user, SocialProvider provider, String providerId) {
        return new SocialAccount(user, provider, providerId);
    }
}
