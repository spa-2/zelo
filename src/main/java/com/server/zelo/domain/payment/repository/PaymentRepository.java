package com.server.zelo.domain.payment.repository;

import com.server.zelo.domain.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPortoneId(String portoneId);
    Optional<Payment> findTopByOrderIdOrderByIdDesc(Long orderId);


}

