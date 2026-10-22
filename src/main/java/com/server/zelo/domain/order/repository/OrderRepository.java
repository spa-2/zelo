package com.server.zelo.domain.order.repository;

import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // 동일 주문의 결제·실패·취소가 동시에 진행되지 않도록 주문 행을 잠금
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findForUpdateById(Long id);

    // Order, OrderItem 같이 조회
    @EntityGraph(attributePaths = "orderItems")
    Optional<Order> findWithOrderItemsById(Long id);

    // 상태 조건 없이 본인 주문 조회
    Page<Order> findByUser_Id(Pageable pageable, Long userId);

    // 상태 조건을 포함해 본인 주문 조회
    Page<Order> findByUser_IdAndStatus(Pageable pageable, Long userId, OrderStatus status);
}