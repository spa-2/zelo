package com.server.zelo.domain.product.repository;

import com.server.zelo.domain.product.entity.Ingredient;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IngredientRepository extends JpaRepository<Ingredient, Long> {

    // 상품 등록 응답에서 카테고리 이름을 읽어서 같이 가져옴 (open-in-view 꺼져 있음)
    @EntityGraph(attributePaths = {"category"})
    Optional<Ingredient> findWithCategoryById(Long id);
}