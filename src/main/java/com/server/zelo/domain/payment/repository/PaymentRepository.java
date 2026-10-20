package com.server.zelo.domain.payment.repository;


import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {


}

