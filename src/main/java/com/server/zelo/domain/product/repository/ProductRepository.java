package com.server.zelo.domain.product.repository;

import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    @EntityGraph(attributePaths = {"ingredient", "category"})
    List<Product> findAllByIngredient_NameInAndStatus(Collection<String> ingredientNames, ProductStatus status);

    // 주문이 동시에 와도 안 꼬이게 읽고 더하지 않고 UPDATE 한 번으로 처리
    // clearAutomatically는 안 씀. 주문 트랜잭션 안에서 불려서 비우면 주문 쪽 엔티티 변경이 날아감
    @Modifying
    @Query("update Product p set p.salesCount = p.salesCount + :quantity where p.id = :productId")
    int increaseSalesCount(Long productId, int quantity);

    // 0 밑으로는 안 내려가게 판매량이 모자라면 안 바꿈
    @Modifying
    @Query("update Product p set p.salesCount = p.salesCount - :quantity where p.id = :productId and p.salesCount >= :quantity")
    int decreaseSalesCount(Long productId, int quantity);
}