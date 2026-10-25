package com.server.zelo.domain.refund.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.delivery.service.DeliveryService;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.service.PaymentService;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.refund.dto.response.CreateRefundResponse;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundItem;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.entity.RefundType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefundCommandService {

    private final OrderService orderService;
    private final PortOneClient portOneClient;
    private final RefundService refundService;
    private final PaymentService paymentService;
    private final DeliveryService deliveryService;
    private final OrderCommandService orderCommandService;
    private final TransactionOperations transactionOperations;
    private static final String CANCEL_KEY_PREFIX = "refund-cancel-";

    // 포트원 취소는 외부 HTTP 호출이라 트랜잭션 밖에서 하고, 요청 생성과 결과 반영만 각각 트랜잭션으로 묶는다
    public CreateRefundResponse createRefund(Long userId, Long orderId, String reason) {
        RefundTarget target = Objects.requireNonNull(
                transactionOperations.execute(status -> requestRefund(userId, orderId, reason)));

        try {
            portOneClient.cancelPaymentWithAmount(target.portoneId(),String.format("%s%012d", CANCEL_KEY_PREFIX, target.refundId()),
                    target.amount(), target.amount(), reason);
        } catch (BusinessException e) {
            if (!isRejected(e)) {
                log.warn("환불 결과 불명확 refundId={}, errorCode={}", target.refundId(), e.getErrorCode());
                return CreateRefundResponse.of(target.refundId(), RefundStatus.REQUESTED);
            }
            transactionOperations.executeWithoutResult(status -> {
                refundService.fail(target.refundId());
                deliveryService.releaseHold(orderId);
            });
            return CreateRefundResponse.of(target.refundId(), RefundStatus.FAILED);
        }

        transactionOperations.executeWithoutResult(status -> {
            refundService.complete(target.refundId(), LocalDateTime.now());
            paymentService.cancel(target.portoneId(), target.amount());
            orderCommandService.cancel(orderId);
            deliveryService.cancelDelivery(orderId);
        });
        return CreateRefundResponse.of(target.refundId(), RefundStatus.COMPLETED);
    }

    private RefundTarget requestRefund(Long userId, Long orderId, String reason) {
        orderService.validateOwner(orderId, userId);
        Payment payment = paymentService.getLatestPayment(orderId);
        payment.validateRefundable();
        refundService.validateNotInProgress(payment.getId());
        deliveryService.holdForRefund(orderId);

        Order order = orderService.getOrderWithItems(orderId);
        Refund refund = Refund.of(payment.getId(), RefundType.FULL, reason);
        order.getOrderItems()
                .forEach(orderItem -> refund.addRefundItem(RefundItem.of(orderItem, orderItem.getQuantity())));
        Refund savedRefund = refundService.createRefund(refund);

        // 지금은 전액 환불만 있어 취소 가능 잔액이 실제 결제 금액과 같다. 부분 환불(R5)에서 이전 환불 금액을 빼도록 바꾼다
        return new RefundTarget(savedRefund.getId(), payment.getPortoneId(), payment.getPgAmount());
    }

    // 취소 웹훅을 받으면 포트원에 다시 조회해, 결과를 모른 채 남은 환불이나 포트원에서 직접 취소한 결제를 맞춘다
    public void syncCancellation(Long orderId, String portoneId) {
        PortOnePayment pgPayment = portOneClient.getPayment(portoneId);
        if (pgPayment.isPaid()) {
            failRequestedRefund(orderId, portoneId);
            return;
        }
        if (!pgPayment.isCancelled()) {
            log.info("포트원에서 취소되지 않은 결제라 동기화하지 않음 portoneId={}, status={}", portoneId, pgPayment.status());
            return;
        }

        transactionOperations.executeWithoutResult(status -> {
            Payment payment = paymentService.getPayment(portoneId);
            // 결제 확인이나 환불 요청에서 이미 취소까지 반영한 결제는 다시 바꾸지 않는다
            if (!payment.isPaid()) {
                return;
            }
            refundService.findRequestedRefund(payment.getId()).ifPresentOrElse(
                    refund -> refundService.complete(refund.getId(), LocalDateTime.now()),
                    () -> log.warn("환불 요청 없이 포트원에서 취소된 결제 portoneId={}", portoneId));
            paymentService.cancel(portoneId, payment.getPgAmount());
            // 이미 출고된 주문은 상품을 회수하기 전이라 주문 취소와 재고 복원을 하지 않고 운영 회수 절차에 맡긴다
            if (deliveryService.cancelDeliveryIfPossible(orderId)) {
                orderCommandService.cancelIfPossible(orderId);
            } else {
                log.warn("출고 후 포트원에서 취소된 주문이라 회수가 필요함 orderId={}", orderId);
            }
        });
    }

    private record RefundTarget(Long refundId, String portoneId, Long amount) {
    }

    private boolean isRejected(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return errorCode == ErrorCode.PG_REQUEST_REJECTED
                || errorCode == ErrorCode.PAYMENT_NOT_FOUND
                || errorCode == ErrorCode.INVALID_INPUT;
    }

    private void failRequestedRefund(Long orderId, String portoneId) {
        transactionOperations.executeWithoutResult(status -> {
            Payment payment = paymentService.getPayment(portoneId);
            refundService.findRequestedRefund(payment.getId()).ifPresent(refund -> {
                refundService.fail(refund.getId());
                deliveryService.releaseHold(orderId);
            });
        });
    }
}