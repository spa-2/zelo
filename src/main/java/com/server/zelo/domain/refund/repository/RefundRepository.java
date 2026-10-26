package com.server.zelo.domain.refund.repository;

import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {

    boolean existsByPaymentIdAndStatus(Long paymentId, RefundStatus status);

    Optional<Refund> findByPaymentIdAndStatus(Long paymentId, RefundStatus status);

    Optional<Refund> findTopByPaymentIdOrderByIdDesc(Long paymentId);
}