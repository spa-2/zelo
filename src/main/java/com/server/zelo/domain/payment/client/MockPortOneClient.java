package com.server.zelo.domain.payment.client;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.client.dto.PortOnePayment;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
// TODO(portone): 실제 포트원 연동 전까지 local, dev에서만 쓰는 가짜 구현이다. 저장된 결제 금액 그대로 결제 완료로 응답한다
@Slf4j
@Component
@RequiredArgsConstructor
public class MockPortOneClient implements PortOneClient {

    private final PaymentRepository paymentRepository;



    @Override
    public PortOnePayment getPayment(String portoneId) {
        Payment payment = paymentRepository.findByPortoneId(portoneId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        return PortOnePayment.of(portoneId, PortOnePaymentStatus.PAID, payment.getAmount(), LocalDateTime.now());
    }

    @Override
    public void cancelPayment(String portoneId, String reason) {
        log.info("[FAKE] 포트원 결제 취소 portoneId={}, reason={}", portoneId, reason);
    }
}
