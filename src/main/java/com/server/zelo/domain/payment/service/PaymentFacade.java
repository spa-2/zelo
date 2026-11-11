package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.payment.dto.response.RetryPaymentResponse;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentFacade {

    private final LockDistributeService lockDistributeService;
    private final PaymentCommandService paymentCommandService;

    // 웹훅·확인 요청·스케줄러가 같은 결제를 동시에 처리하지 않게 결제 단위로 락을 건다
    public PaymentStatus confirmPayment(String portoneId){
        return lockDistributeService.execute(LockKey.confirmPayment(portoneId),
                () -> paymentCommandService.confirmPayment(portoneId));
    }

    public PaymentStatus confirmPayment(Long userId, String portoneId) {
        return lockDistributeService.execute(LockKey.confirmPayment(portoneId),
                () -> paymentCommandService.confirmPayment(userId, portoneId));
    }

    // 빌링키 결제 중에도 웹훅·스케줄러와 겹치지 않게 결제 확인 락을 쓴다
    public PaymentStatus payWithPaymentMethod(Long userId, String portoneId, Long paymentMethodId) {
        return lockDistributeService.execute(LockKey.confirmPayment(portoneId),
                () -> paymentCommandService.payWithPaymentMethod(userId, portoneId, paymentMethodId));
    }

    // 재결제 연타는 주문 단위로 막고, 확인 대기 결제는 결제 확인 락 안에서 다시 조회한 뒤 재결제한다
    public RetryPaymentResponse retryPayment(Long userId, Long orderId) {
        return lockDistributeService.execute(LockKey.retryPayment(orderId), () -> {
            paymentCommandService.findConfirmPendingPortoneId(userId, orderId).ifPresent(this::recheckConfirmPending);
            return paymentCommandService.retryPayment(userId, orderId);
        });
    }

    public PaymentStatus closeConfirmPending(String portoneId) {
        return lockDistributeService.execute(LockKey.confirmPayment(portoneId),
                () -> paymentCommandService.closeConfirmPending(portoneId));
    }

    public PaymentStatus closeReady(String portoneId) {
        return lockDistributeService.execute(LockKey.confirmPayment(portoneId),
                () -> paymentCommandService.closeReady(portoneId));
    }

    // 다시 조회해도 결과를 모르면 PG 오류 대신 확인 대기로 응답한다
    private void recheckConfirmPending(String portoneId) {
        try {
            confirmPayment(portoneId);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PG_TIMEOUT || e.getErrorCode() == ErrorCode.PG_REQUEST_FAILED) {
                throw new BusinessException(ErrorCode.PAYMENT_CONFIRM_PENDING);
            }
            throw e;
        }
    }
}