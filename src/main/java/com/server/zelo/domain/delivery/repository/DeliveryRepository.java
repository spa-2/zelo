package com.server.zelo.domain.delivery.repository;

import com.server.zelo.domain.delivery.entity.Delivery;
import com.server.zelo.domain.delivery.entity.DeliveryStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    // 상태가 바뀐 지 일정 시간이 지난 배송을 오래된 것부터 limit 건까지 찾는다
    List<Delivery> findByStatusAndUpdatedAtBeforeOrderByIdAsc(DeliveryStatus status, LocalDateTime before, Limit limit);
}