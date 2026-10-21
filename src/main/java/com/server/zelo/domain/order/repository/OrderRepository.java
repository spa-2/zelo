package com.server.zelo.domain.order.repository;

import com.server.zelo.domain.order.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // 동일 주문의 결제·실패·취소가 동시에 진행되지 않도록 주문 행을 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findForUpdateById(Long id);

    @EntityGraph(attributePaths = "orderItems")
    Optional<Order> findWithOrderItemsById(Long id);
}