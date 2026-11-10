package com.server.zelo.domain.delivery.repository;

import com.server.zelo.domain.delivery.entity.Delivery;
import com.server.zelo.domain.delivery.entity.DeliveryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    // 상세 조회가 스케줄러·환불의 배송 상태 변경을 막지 않도록 잠금 없이 조회한다
    Optional<Delivery> findDeliveryByOrderId(Long orderId);

    // 환불 보류된 배송은 빼고, 상태가 바뀐 지 오래된 순으로 limit건 찾는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Delivery> findByStatusAndOnHoldFalseAndUpdatedAtBeforeOrderByIdAsc(
            DeliveryStatus status, LocalDateTime before, Limit limit);

    // 환불과 스케줄러가 같은 배송을 동시에 바꾸지 않게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Delivery> findByOrderId(Long orderId);
}