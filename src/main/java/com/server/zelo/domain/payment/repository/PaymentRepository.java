package com.server.zelo.domain.payment.repository;

import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPortoneId(String portoneId);

    Optional<Payment> findTopByOrderIdOrderByIdDesc(Long orderId);

    List<Payment> findByStatusAndUpdatedAtBeforeOrderByIdAsc(PaymentStatus status, LocalDateTime cutoff, Pageable pageable);
}

