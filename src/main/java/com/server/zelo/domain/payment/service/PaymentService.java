package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PORTONE_ID_PREFIX = "payment-";

    private final PaymentRepository paymentRepository;

    // 주문이 결제 대기 상태가 될 때 주문 Command의 트랜잭션 안에서 호출한다.
    // 포트원 결제창을 띄우기 전에 서버가 결제 ID를 발급해야 웹훅이 와도 어느 주문의 결제인지 찾을 수 있다
    public Payment createPayment(Long orderId, Long amount) {
        String portoneId = PORTONE_ID_PREFIX + UUID.randomUUID();
        return paymentRepository.save(Payment.of(orderId, portoneId, amount));
    }

    public Payment getPayment(String portoneId) {
        return paymentRepository.findByPortoneId(portoneId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    // 이전 확인이 중간에 끊겨 이미 확인 대기인 결제는 상태를 바꾸지 않고 그대로 다시 확인한다
    public Payment startConfirm(String portoneId) {
        Payment payment = getPayment(portoneId);
        if (payment.isReady()) {
            payment.startConfirm();
        }
        return payment;
    }

    public void complete(String portoneId, Long pgAmount, LocalDateTime paidAt) {
        getPayment(portoneId).complete(pgAmount, paidAt);
    }

    public void fail(String portoneId) {
        getPayment(portoneId).fail();
    }

    public void cancel(String portoneId, Long pgAmount) {
        getPayment(portoneId).cancel(pgAmount);
    }
}