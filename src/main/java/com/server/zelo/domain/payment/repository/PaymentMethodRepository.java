package com.server.zelo.domain.payment.repository;

import com.server.zelo.domain.payment.entity.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {

    boolean existsByBillingKey(String billingKey);

    Optional<PaymentMethod> findByIdAndUserId(Long id, Long userId);
}