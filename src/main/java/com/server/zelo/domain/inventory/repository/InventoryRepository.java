package com.server.zelo.domain.inventory.repository;

import com.server.zelo.domain.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    // 상품 ID로 재고 조회
    Optional<Inventory> findInventoryByProduct_Id(Long productId);
}