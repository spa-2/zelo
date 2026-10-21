package com.server.zelo.domain.inventory.repository;

import com.server.zelo.domain.inventory.entity.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    // 상품 ID로 재고 조회. 같은 상품의 재고를 여러 주문이 동시에 변경하지 못하도록 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Inventory> findInventoryByProduct_Id(Long productId);
}