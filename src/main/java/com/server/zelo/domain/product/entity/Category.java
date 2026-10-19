package com.server.zelo.domain.product.entity;

import com.server.zelo.common.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "categories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 50, unique = true)
    private String name;

    // false면 AI가 주문할 수 없는 카테고리 (주류같은 성인인증이 필요한 항ㅂ목)
    @Column(name = "agent_orderable", nullable = false)
    private boolean isAgentOrderable;

    /**
     * 카테고리 이름과 AI 주문 허용 여부를 초기화한다.
     */
    private Category(String name, boolean isAgentOrderable) {
        this.name = name;
        this.isAgentOrderable = isAgentOrderable;
    }

    /**
     * 지정한 이름과 AI 주문 허용 여부로 카테고리를 생성한다.
     *
     * @param name 카테고리 이름
     * @param isAgentOrderable AI 주문 허용 여부
     * @return 생성한 카테고리
     */
    public static Category of(String name, boolean isAgentOrderable) {
        return new Category(name, isAgentOrderable);
    }
}