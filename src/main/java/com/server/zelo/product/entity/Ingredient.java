package com.server.zelo.product.entity;

import com.server.zelo.common.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "ingredients")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ingredient extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    // 재료 분류명 ex:(김치, 대파, 계란)
    @Column(name = "name", nullable = false, length = 50, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_unit", nullable = false, length = 10)
    private BaseUnit baseUnit;

    private Ingredient(Category category, String name, BaseUnit baseUnit) {
        this.category = category;
        this.name = name;
        this.baseUnit = baseUnit;
    }

    public static Ingredient of(Category category, String name, BaseUnit baseUnit) {
        return new Ingredient(category, name, baseUnit);
    }
}