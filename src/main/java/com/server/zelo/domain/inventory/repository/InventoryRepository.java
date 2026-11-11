package com.server.zelo.domain.inventory.repository;

import com.server.zelo.domain.inventory.entity.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    // 상품 ID로 재고 조회. 같은 상품의 재고를 여러 주문이 동시에 변경하지 못하도록 잠금.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Inventory> findInventoryByProduct_Id(Long productId);

    // 상품 ID 목록 조회. IN으로 한 번에 조회해서 상품 수만큼 쿼리가 나가지 않음.
    List<Inventory> findAllByProduct_IdIn(Collection<Long> productIds);
}