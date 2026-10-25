package com.server.zelo.domain.refund.repository;

import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {

    boolean existsByPaymentIdAndStatus(Long paymentId, RefundStatus status);
}