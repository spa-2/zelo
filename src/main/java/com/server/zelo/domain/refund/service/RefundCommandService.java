package com.server.zelo.domain.refund.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.delivery.service.DeliveryService;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
    private static final String EXTERNAL_CANCEL_REASON = "포트원에서 직접 취소";

    // 포트원 취소는 외부 HTTP 호출이라 트랜잭션 밖에서 하고, 요청 생성과 결과 반영만 각각 트랜잭션으로 묶는다
    public CreateRefundResponse createRefund(Long userId, Long orderId, String reason,
                                             Map<Long, Integer> cancelQuantities) {
        RefundTarget target = Objects.requireNonNull(transactionOperations.execute(
                status -> requestRefund(userId, orderId, reason, cancelQuantities)));

        try {
            portOneClient.cancelPaymentWithAmount(target.portoneId(),String.format("%s%012d", CANCEL_KEY_PREFIX, target.refundId()),
                    target.amount(), target.cancellableAmount(), reason);
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
            orderCommandService.cancelItems(orderId, target.cancelQuantities());
            // 남은 상품이 없으면 보낼 것도 없으므로 결제와 배송을 취소하고, 남아 있으면 보류만 풀어 출고되게 한다
            if (target.cancelsAll()) {
                paymentService.cancel(target.portoneId(), target.pgAmount());
                deliveryService.cancelDelivery(orderId);
            } else {
                deliveryService.releaseHold(orderId);
            }
        });
        return CreateRefundResponse.of(target.refundId(), RefundStatus.COMPLETED);
    }

    private RefundTarget requestRefund(Long userId, Long orderId, String reason, Map<Long, Integer> requested) {
        orderService.validateOwner(orderId, userId);
        Payment payment = paymentService.getLatestPayment(orderId);
        payment.validateRefundable();
        refundService.validateNotInProgress(payment.getId());
        deliveryService.holdForRefund(orderId);

        Order order = orderService.getOrderWithItems(orderId);
        // 환불할 상품을 고르지 않으면 남은 상품 전부를 환불한다
        Map<Long, Integer> cancelQuantities = requested.isEmpty() ? remainingQuantities(order) : requested;
        boolean cancelsAll = isAllRemaining(order, cancelQuantities);

        Refund refund = Refund.of(payment.getId(), cancelsAll ? RefundType.FULL : RefundType.PARTIAL, reason);
        Map<Long, OrderItem> orderItems = order.getOrderItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, orderItem -> orderItem));
        cancelQuantities.forEach((orderItemId, quantity) -> {
            OrderItem orderItem = orderItems.get(orderItemId);
            if (orderItem == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "해당 주문에 속한 주문 상품만 환불할 수 있습니다.");
            }
            // 이미 환불한 수량을 빼고 남은 수량까지만 환불할 수 있다
            orderItem.validateCancelQuantity(quantity);
            refund.addRefundItem(RefundItem.of(orderItem, quantity));
        });
        Refund savedRefund = refundService.createRefund(refund);

        long cancellableAmount = payment.getPgAmount() - refundService.calculateRefundedAmount(payment.getId());
        // 남은 상품을 모두 환불하면 남은 결제 금액 전부를, 일부면 상품 금액만 취소한다
        long amount = cancelsAll ? cancellableAmount : savedRefund.getItemsAmount();
        return new RefundTarget(savedRefund.getId(), payment.getPortoneId(), amount, cancellableAmount,
                payment.getPgAmount(), cancelQuantities, cancelsAll);
    }

    private Map<Long, Integer> remainingQuantities(Order order) {
        return order.getOrderItems().stream()
                .filter(orderItem -> orderItem.getCancelableQuantity() > 0)
                .collect(Collectors.toMap(OrderItem::getId, OrderItem::getCancelableQuantity));
    }

    // 이번 환불로 모든 상품의 남은 수량이 0이 되는지 확인한다
    private boolean isAllRemaining(Order order, Map<Long, Integer> cancelQuantities) {
        return order.getOrderItems().stream()
                .allMatch(orderItem -> orderItem.getCancelableQuantity()
                        .equals(cancelQuantities.getOrDefault(orderItem.getId(), 0)));
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
                    () -> recordCancellation(orderId, payment.getId()));
            paymentService.cancel(portoneId, payment.getPgAmount());
            // 이미 출고된 주문은 상품을 회수하기 전이라 주문 취소와 재고 복원을 하지 않고 운영 회수 절차에 맡긴다
            if (deliveryService.cancelDeliveryIfPossible(orderId)) {
                orderCommandService.cancelIfPossible(orderId);
            } else {
                log.warn("출고 후 포트원에서 취소된 주문이라 회수가 필요함 orderId={}", orderId);
            }
        });
    }

    private record RefundTarget(Long refundId, String portoneId, Long amount, Long cancellableAmount,
                                Long pgAmount, Map<Long, Integer> cancelQuantities, boolean cancelsAll) {
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

    // 주문 내역의 결제 금액이 맞도록 남은 상품 전부를 환불한 기록을 완료 상태로 남긴다
    private void recordCancellation(Long orderId, Long paymentId) {
        log.warn("환불 요청 없이 포트원에서 취소된 결제 orderId={}, paymentId={}", orderId, paymentId);
        Order order = orderService.getOrderWithItems(orderId);
        Refund refund = Refund.of(paymentId, RefundType.FULL, EXTERNAL_CANCEL_REASON);
        order.getOrderItems().stream()
                .filter(orderItem -> orderItem.getCancelableQuantity() > 0)
                .forEach(orderItem -> refund.addRefundItem(RefundItem.of(orderItem, orderItem.getCancelableQuantity())));
        if (refund.getRefundItems().isEmpty()) {
            return;
        }
        refund.complete(LocalDateTime.now());
        refundService.createRefund(refund);
    }

}