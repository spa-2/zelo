package com.server.zelo.domain.portone.client.mock;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.PaymentMethodType;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOneBillingKey;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import com.server.zelo.domain.portone.entity.PortOnePaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;


@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.portone.mode", havingValue = "fake")
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

    @Override
    public void cancelPaymentWithAmount(String portoneId, String cancelKey, Long amount,
                                        Long currentCancellableAmount, String reason) {
        log.info("[FAKE] 포트원 결제 금액 지정 취소 portoneId={}, cancelKey={}, amount={}, cancellable={}, reason={}",
                portoneId, cancelKey, amount, currentCancellableAmount, reason);
    }

    // 가짜 모드에는 결제창이 없어 빌링키를 발급받을 수 없으므로, 빌링키 끝에 붙인 사용자 ID를 발급 고객으로 본다 (예: fake-billing-key-1)
    @Override
    public PortOneBillingKey getBillingKey(String billingKey) {
        String customerId = billingKey.substring(billingKey.lastIndexOf('-') + 1);
        return PortOneBillingKey.of(billingKey, PaymentMethodType.CARD, "테스트카드 1234", customerId);
    }
}
