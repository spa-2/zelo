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
import com.server.zelo.domain.refund.dto.response.CreateRefundResponse;
import com.server.zelo.domain.refund.entity.Refund;
import com.server.zelo.domain.refund.entity.RefundItem;
import com.server.zelo.domain.refund.entity.RefundStatus;
import com.server.zelo.domain.refund.entity.RefundType;
import com.server.zelo.domain.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RefundCommandService {

    private static final String CANCEL_KEY_PREFIX = "refund-";

    private final OrderService orderService;
    private final PortOneClient portOneClient;
    private final RefundService refundService;
    private final PaymentService paymentService;
    private final DeliveryService deliveryService;
    private final OrderCommandService orderCommandService;
    private final TransactionOperations transactionOperations;

    // 포트원 취소는 외부 HTTP 호출이라 트랜잭션 밖에서 하고, 요청 생성과 결과 반영만 각각 트랜잭션으로 묶는다
    public CreateRefundResponse createRefund(Long userId, Long orderId, String reason) {
        RefundTarget target = transactionOperations.execute(status -> requestRefund(userId, orderId, reason));

        try {
            portOneClient.cancelPaymentWithAmount(target.portoneId(), CANCEL_KEY_PREFIX + target.refundId(),
                    target.amount(), target.amount(), reason);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PG_TIMEOUT) {
                // 포트원에서 취소됐는지 알 수 없어 REQUESTED와 배송 보류를 그대로 둔다. 취소 웹훅(R7)이 마무리한다
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

    private record RefundTarget(Long refundId, String portoneId, Long amount) {
    }
}