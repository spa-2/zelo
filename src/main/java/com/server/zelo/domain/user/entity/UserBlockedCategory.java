package com.server.zelo.domain.user.entity;

import com.server.zelo.common.entity.BaseCreatedEntity;
import com.server.zelo.domain.product.entity.Category;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 사용자가 견적에서 빼 달라고 막은 카테고리 (알레르기 등). 대분류를 막으면 그 아래 소분류도 같이 막힘
@Getter
@Entity
@Table(
        name = "user_blocked_categories",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_blocked_category", columnNames = {"user_id", "category_id"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBlockedCategory extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    private UserBlockedCategory(User user, Category category) {
        this.user = user;
        this.category = category;
    }

    public static UserBlockedCategory of(User user, Category category) {
        return new UserBlockedCategory(user, category);
    }
}