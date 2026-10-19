package com.server.zelo.domain.payment.service;

import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PORTONE_ID_PREFIX = "payment-";

    private final PaymentRepository paymentRepository;

    public Optional<Payment> findReadyPayment(Long orderId) {
        return paymentRepository.findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.READY);
    }

    // 포트원 결제창을 띄우기 전에 서버가 결제 ID를 발급해야 웹훅이 와도 어느 주문의 결제인지 찾을 수 있다
    public Payment createPayment(Long orderId, Long amount) {
        String portoneId = PORTONE_ID_PREFIX + UUID.randomUUID();
        return paymentRepository.save(Payment.of(orderId, portoneId, amount));
    }
}