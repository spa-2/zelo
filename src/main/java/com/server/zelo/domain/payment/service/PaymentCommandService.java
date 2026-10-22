package com.server.zelo.domain.payment.service;

import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
@RequiredArgsConstructor
public class PaymentCommandService {

    private static final String AMOUNT_MISMATCH_REASON = "결제 금액 불일치";

    private final OrderService orderService;
    private final PortOneClient portOneClient;
    private final PaymentService paymentService;
    private final OrderCommandService orderCommandService;
    private final TransactionOperations transactionOperations;

    public PaymentStatus confirmPayment(Long userId, String portoneId) {
        Payment payment = paymentService.getPayment(portoneId);
        orderService.validateOwner(payment.getOrderId(), userId);
        return confirmPayment(portoneId);
    }

    // 포트원 조회와 환불은 외부 HTTP 호출이라 DB 커넥션을 붙잡지 않도록 트랜잭션 밖에서 하고, 상태 반영만 트랜잭션으로 묶는다
    public PaymentStatus confirmPayment(String portoneId) {
        Payment payment = transactionOperations.execute(status -> paymentService.startConfirm(portoneId));
        if (payment.isConfirmed()) {
            return payment.getStatus();
        }

        PortOnePayment pgPayment = portOneClient.getPayment(portoneId);
        Long orderId = payment.getOrderId();

        if (pgPayment.isPaid() && payment.isAmountMatched(pgPayment.amount())) {
            transactionOperations.executeWithoutResult(status -> {
                paymentService.complete(portoneId, pgPayment.amount(), pgPayment.paidAt());
                orderCommandService.completePayment(orderId);
            });
            return PaymentStatus.PAID;
        }

        if (pgPayment.isPaid()) {
            // 이미 돈이 빠져나간 상태라 실패 처리만 하면 고객 돈이 묶이므로 포트원에 환불을 먼저 요청한다
            portOneClient.cancelPayment(portoneId, AMOUNT_MISMATCH_REASON);
            transactionOperations.executeWithoutResult(status -> {
                paymentService.cancel(portoneId, pgPayment.amount());
                orderCommandService.cancel(orderId);
            });
            return PaymentStatus.CANCELED;
        }

        if (pgPayment.isFailed()) {
            transactionOperations.executeWithoutResult(status -> {
                paymentService.fail(portoneId);
                orderCommandService.failPayment(orderId);
            });
            return PaymentStatus.FAILED;
        }
        // 그 외(결제창 진행 중 등)는 확인 대기로 남겨 두고 웹훅이나 스케줄러가 다시 확인한다
        return PaymentStatus.CONFIRM_PENDING;
    }
}