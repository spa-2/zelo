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

    // 견적 후보 상품 ID. 재료마다 (카테고리, 맞춘 조건 키워드)별로 판매량 상위 limit개 + 필요한 양을 샀을 때 총액이 싼 limit개
    // 견적 점수(판매량 40 + 리뷰 30 + 총액 30)에서 이길 상품은 거의 판매량이나 총액 쪽 상위라 여기서만 고른다
    // 상품이 10만 개면 김치만 7천 개가 넘어서, 재료의 상품을 다 불러와 점수를 매기면 견적 하나에 0.7초가 걸렸음
    // amounts: {"김치": 400, ...} (재료 이름 → 기본 단위 필요량), conditions: 조건 키워드를 쉼표로 이은 것 (없으면 빈 문자열)
    @Query(value = """
            select ranked.id from (
                select p.id,
                       row_number() over (partition by p.ingredient_id, p.category_id, matched.keywords
                                          order by p.sales_count desc, p.id) as by_sales,
                       row_number() over (partition by p.ingredient_id, p.category_id, matched.keywords
                                          order by p.price * ceil(cast(required.amount as numeric) / p.content_amount), p.id) as by_price
                from products p
                join ingredients i on i.id = p.ingredient_id
                join jsonb_each_text(cast(:amounts as jsonb)) as required(name, amount) on required.name = i.name
                cross join lateral (
                    select coalesce(string_agg(k.keyword, ',' order by k.keyword), '') as keywords
                    from product_keywords k
                    where k.product_id = p.id and k.keyword = any(string_to_array(:conditions, ','))
                ) matched
                where p.status = 'ON_SALE'
            ) ranked
            where ranked.by_sales <= :limit or ranked.by_price <= :limit
            """, nativeQuery = true)
    List<Long> findQuoteCandidateIds(String amounts, String conditions, int limit);

    @EntityGraph(attributePaths = {"ingredient", "category"})
    List<Product> findAllWithIngredientByIdIn(Collection<Long> ids);

    // keyword는 %, _를 이스케이프해서 넘김 (ProductService.escapeLike)
    // 대분류로 찾으면 그 아래 소분류 상품까지 나오게 parent도 같이 봄 (대분류는 parent가 없어서 left join)
    // 재고 있는 것만은 페이지 나누기 전에 걸러야 해서 Inventory를 서브쿼리로 확인
    @EntityGraph(attributePaths = {"category"})
    @Query("""
            select p from Product p
            join p.category c
            left join c.parent pc
            where p.status = :status
              and (p.name like concat('%', :keyword, '%') escape '\\' or p.brand like concat('%', :keyword, '%') escape '\\')
              and (:categoryId is null or c.id = :categoryId or pc.id = :categoryId)
              and (:inStock = false or exists (
                    select 1 from Inventory i where i.product = p and i.availableQuantity > 0))
            """)
    Page<Product> search(String keyword, Long categoryId, boolean inStock, ProductStatus status, Pageable pageable);

    // 자동완성용. 브랜드만 다르고 이름이 같은 상품은 한 번만, 많이 팔린 이름부터
    @Query("""
            select p.name from Product p
            where p.status = :status
              and p.name like concat('%', :keyword, '%') escape '\\'
            group by p.name
            order by max(p.salesCount) desc, p.name
            """)
    List<String> findNames(String keyword, ProductStatus status, Limit limit);

    // 같은 쿼리를 5번 넘게 쓰면 PostgreSQL이 검색어를 안 보고 짠 계획을 재사용해서 검색이 20ms → 1.3초로 느려짐
    // 검색·자동완성 트랜잭션 안에서만 매번 실제 검색어로 계획을 짜게 함. 트랜잭션이 끝나면 원래대로 돌아감
    @Query(value = "select set_config('plan_cache_mode', 'force_custom_plan', true)", nativeQuery = true)
    String useCustomPlanInTransaction();

    // 인기 상품 목록에 카테고리 이름을 같이 보여줘서 같이 가져옴
    @EntityGraph(attributePaths = {"category"})
    List<Product> findAllByIdInAndStatus(Collection<Long> ids, ProductStatus status);

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