package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.dto.response.PaymentFindTarget;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PORTONE_ID_PREFIX = "payment-";
    private static final int STALE_CONFIRM_BATCH_SIZE = 20;
    private final PaymentRepository paymentRepository;

    // 포트원 결제창을 띄우기 전에 서버가 결제 ID를 발급해야 웹훅이 와도 어느 주문의 결제인지 찾을 수 있다
    public Payment createPayment(Long orderId, Long amount) {
        String portoneId = PORTONE_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
        return paymentRepository.save(Payment.of(orderId, portoneId, amount));
    }

    public Payment getPayment(String portoneId) {
        return paymentRepository.findByPortoneId(portoneId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findLatestPayment(Long orderId) {
        return paymentRepository.findTopByOrderIdOrderByIdDesc(orderId);
    }

    @Transactional(readOnly = true)
    public List<PaymentFindTarget> findConfirmPendingPayments(LocalDateTime cutoff, Long afterId) {
        return paymentRepository.findByStatusAndUpdatedAtBeforeAndIdGreaterThanOrderByIdAsc(
                        PaymentStatus.CONFIRM_PENDING, cutoff, afterId, PageRequest.of(0, STALE_CONFIRM_BATCH_SIZE))
                .stream()
                .map(PaymentFindTarget::from)
                .toList();
    }

    // 이전 확인이 중간에 끊겨 이미 확인 대기인 결제는 상태를 바꾸지 않고 그대로 다시 확인한다
    public Payment startConfirm(String portoneId) {
        Payment payment = getPayment(portoneId);
        if (payment.isReady()) {
            payment.startConfirm();
        }
        return payment;
    }

    public void complete(String portoneId, Long pgAmount, LocalDateTime paidAt, String paymentMethodName) {
        getPayment(portoneId).complete(pgAmount, paidAt, paymentMethodName);
    }

    public void cancelLatePaid(String portoneId, Long pgAmount) {
        getPayment(portoneId).cancelLatePaid(pgAmount);
    }

    public void fail(String portoneId, String failureReason) {getPayment(portoneId).fail(failureReason);}

    public void cancel(String portoneId, Long pgAmount) {
        getPayment(portoneId).cancel(pgAmount);
    }

    public Payment getLatestPayment(Long orderId) {
        return paymentRepository.findTopByOrderIdOrderByIdDesc(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    public Payment startBillingPayment(String portoneId) {
        Payment payment = getPayment(portoneId);
        payment.validateReady();
        payment.startConfirm();
        return payment;
    }
}