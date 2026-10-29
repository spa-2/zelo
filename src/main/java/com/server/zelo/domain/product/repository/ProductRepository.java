package com.server.zelo.domain.product.repository;

import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.entity.ProductStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    @EntityGraph(attributePaths = {"ingredient", "category"})
    List<Product> findAllByIngredient_NameInAndStatus(Collection<String> ingredientNames, ProductStatus status);

    // 대분류로 찾으면 그 아래 소분류 상품까지 나오게 parent도 같이 봄 (대분류는 parent가 없어서 left join)
    // 재고 있는 것만은 페이지 나누기 전에 걸러야 해서 Inventory를 서브쿼리로 확인
    @EntityGraph(attributePaths = {"category"})
    @Query("""
            select p from Product p
            join p.category c
            left join c.parent pc
            where p.status = :status
              and (p.name like concat('%', :keyword, '%') or p.brand like concat('%', :keyword, '%'))
              and (:categoryId is null or c.id = :categoryId or pc.id = :categoryId)
              and (:inStock = false or exists (
                    select 1 from Inventory i where i.product = p and i.availableQuantity > 0))
            """)
    Page<Product> search(String keyword, Long categoryId, boolean inStock, ProductStatus status, Pageable pageable);

    // 자동완성용. 브랜드만 다르고 이름이 같은 상품은 한 번만, 많이 팔린 이름부터
    @Query("""
            select p.name from Product p
            where p.status = :status
              and p.name like concat('%', :keyword, '%')
            group by p.name
            order by max(p.salesCount) desc, p.name
            """)
    List<String> findNames(String keyword, ProductStatus status, Limit limit);

    @EntityGraph(attributePaths = {"category"})
    Optional<Product> findByProductCode(String productCode);
    boolean existsByProductCode(String productCode);

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