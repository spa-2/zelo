package com.server.zelo.domain.product.entity;

import com.server.zelo.common.entity.BaseCreatedEntity;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    // 상위 카테고리. null이면 대분류(ex;채소), 값이 있으면 소분류(ex:양파·대파·마늘·배추)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", nullable = true)
    private Category parent;

    @Column(name = "name", nullable = false, length = 50, unique = true)
    private String name;

    // false면 AI가 주문할 수 없는 카테고리 (주류같은 성인인증이 필요한 항ㅂ목)
    @Column(name = "agent_orderable", nullable = false)
    private boolean isAgentOrderable;

    /**
     * 상위 카테고리, 이름, AI 주문 허용 여부를 초기화
     */
    private Category(Category parent, String name, boolean isAgentOrderable) {
        this.parent = parent;
        this.name = name;
        this.isAgentOrderable = isAgentOrderable;
    }

    /**
     * 지정한 이름과 AI 주문 허용 여부로 대분류 카테고리를 생성
     *
     * @param name 카테고리 이름
     * @param isAgentOrderable AI 주문 허용 여부
     * @return 생성한 대분류 카테고리
     */
    public static Category of(String name, boolean isAgentOrderable) {
        return new Category(null, name, isAgentOrderable);
    }

    /**
     * 대분류 아래에 소분류 카테고리를 생성. AI 주문 허용 여부는 대분류를 따른다.
     *
     * @param parent 소분류가 속할 대분류 카테고리
     * @param name 소분류 이름
     * @return 생성한 소분류 카테고리
     * @throws BusinessException 상위 카테고리가 없거나 소분류이면 {@link ErrorCode#INVALID_INPUT}으로 발생
     */
    public static Category of(Category parent, String name) {
        if (parent == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "소분류의 상위 카테고리가 필요합니다.");
        }
        if (parent.getParent() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "소분류 아래에는 카테고리를 만들 수 없습니다.");
        }
        // 주류 아래 소분류가 AI 주문 가능으로 잘못 등록되지 않도록 대분류 값을 그대로 따른다
        return new Category(parent, name, parent.isAgentOrderable());
    }
}