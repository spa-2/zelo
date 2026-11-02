package com.server.zelo.domain.order.repository;

import com.server.zelo.domain.order.entity.OrderAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderAuditLogRepository extends JpaRepository<OrderAuditLog, Long> {

    List<OrderAuditLog> findByOrderIdOrderByIdAsc(Long orderId);

    List<OrderAuditLog> findByUserIdOrderByIdAsc(Long userId);
}
