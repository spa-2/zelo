package com.server.zelo.domain.payments.repository;

import com.server.zelo.domain.payments.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
