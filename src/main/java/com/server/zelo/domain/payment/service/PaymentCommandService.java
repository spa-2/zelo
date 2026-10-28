package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.response.PaymentCardPayload;
import com.server.zelo.domain.chat.service.ChatMessageService;
import com.server.zelo.domain.delivery.service.DeliveryService;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.service.OrderCommandService;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentMethod;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCommandService {

    private static final String AMOUNT_MISMATCH_REASON = "결제 금액 불일치";
    private static final String CLOSED_ORDER_REASON = "결제 대기 중이 아닌 주문";

    private final OrderService orderService;
    private final PortOneClient portOneClient;
    private final PaymentService paymentService;
    private final DeliveryService deliveryService;
    private final ChatMessageService chatMessageService;
    private final OrderCommandService orderCommandService;
    private final PaymentMethodService paymentMethodService;
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

        if (pgPayment.isPaid() && !isPaymentPending(orderId)) {
            // 주문이 먼저 취소되어 결제를 반영할 수 없으므로, 이미 빠져나간 돈을 돌려주고 결제만 취소로 맞춘다
            portOneClient.cancelPayment(portoneId, CLOSED_ORDER_REASON);
            transactionOperations.executeWithoutResult(
                    status -> paymentService.cancel(portoneId, pgPayment.amount()));
            createPaymentCard(orderId, PaymentStatus.CANCELED, PaymentCardPayload.ORDER_CLOSED, pgPayment.amount(), null);
            return PaymentStatus.CANCELED;
        }

        if (pgPayment.isPaid() && payment.isAmountMatched(pgPayment.amount())) {
            transactionOperations.executeWithoutResult(status -> {
                paymentService.complete(portoneId, pgPayment.amount(), pgPayment.paidAt(), pgPayment.methodName());
                orderCommandService.completePayment(orderId);
                createDelivery(orderId);
            });
            createPaymentCard(orderId, PaymentStatus.PAID, null, pgPayment.amount(), pgPayment.paidAt());
            return PaymentStatus.PAID;
        }

        if (pgPayment.isPaid()) {
            // 이미 돈이 빠져나간 상태라 실패 처리만 하면 고객 돈이 묶이므로 포트원에 환불을 먼저 요청한다
            portOneClient.cancelPayment(portoneId, AMOUNT_MISMATCH_REASON);
            transactionOperations.executeWithoutResult(status -> {
                paymentService.cancel(portoneId, pgPayment.amount());
                orderCommandService.cancel(orderId);
            });
            createPaymentCard(orderId, PaymentStatus.CANCELED, PaymentCardPayload.AMOUNT_MISMATCH, pgPayment.amount(), null);
            return PaymentStatus.CANCELED;
        }

        if (pgPayment.isFailed()) {
            return failPayment(portoneId, orderId);
        }

        // 결제창에서 취소된 결제는 실패가 아니라 주문 취소로 반영한다
        if (pgPayment.isCancelled()) {
            transactionOperations.executeWithoutResult(status -> {
                paymentService.cancel(portoneId, pgPayment.amount());
                orderCommandService.cancelIfPossible(orderId);
            });
            createPaymentCard(orderId, PaymentStatus.CANCELED, PaymentCardPayload.USER_CANCELED, null, null);
            return PaymentStatus.CANCELED;
        }

        // 그 외(결제창 진행 중 등)는 확인 대기로 남겨 두고 웹훅이나 스케줄러가 다시 확인한다
        return PaymentStatus.CONFIRM_PENDING;

    }

    // 결제창 없이 서버가 빌링키로 결제하고, 결과는 일반 결제와 같이 포트원 재조회로 반영한다
    public PaymentStatus payWithPaymentMethod(Long userId, String portoneId, Long paymentMethodId) {
        Payment payment = paymentService.getPayment(portoneId);
        Long orderId = payment.getOrderId();
        orderService.validateOwner(orderId, userId);
        PaymentMethod paymentMethod = paymentMethodService.getOwnedMethod(paymentMethodId, userId);

        Order order = orderService.getOrderWithItems(orderId);
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }

        transactionOperations.executeWithoutResult(status -> paymentService.startBillingPayment(portoneId));
        try {
            portOneClient.payWithBillingKey(portoneId, paymentMethod.getBillingKey(), order.getOrderName(), payment.getAmount());
        } catch (BusinessException e) {
            // 카드 거절은 돈이 빠져나가지 않았으므로 바로 실패 처리한다. 타임아웃 등은 결제됐을 수 있어 확인 대기로 남기고 웹훅이 반영한다
            if (e.getErrorCode() == ErrorCode.PG_REQUEST_REJECTED) {
                return failPayment(portoneId, orderId);
            }
            throw e;
        }
        return confirmPayment(portoneId);
    }
        private boolean isPaymentPending(Long orderId) {
        return orderService.getOrderWithItems(orderId).getStatus() == OrderStatus.PAYMENT_PENDING;
    }


    // 결제가 완료된 주문은 주문할 때 저장한 배송지로 배송을 접수한다
    private void createDelivery(Long orderId) {
        Order order = orderService.getOrderWithItems(orderId);
        deliveryService.createDelivery(orderId, order.getShippingAddress());
    }

    // 결제는 이미 커밋돼서, 채팅 카드 저장이 실패해도 결제 확인 응답은 그대로 나가게 한다
    // paidAmount는 실제로 돈이 나간 경우(결제 완료, 환불)에만 넘긴다
    private void createPaymentCard(Long orderId, PaymentStatus paymentStatus, String reason,
                                   Long paidAmount, LocalDateTime paidAt) {
        try {
            transactionOperations.executeWithoutResult(status -> {
                Order order = orderService.getOrderWithItems(orderId);
                if (order.getChatRoom() == null) {
                    return;
                }
                chatMessageService.createPaymentCardMessage(order.getChatRoom().getId(), order.getUser().getId(),
                        PaymentCardPayload.from(order, paymentStatus, reason, paidAmount, paidAt));
            });
        } catch (RuntimeException e) {
            log.error("결제 결과 카드 저장 실패 orderId={}, status={}", orderId, paymentStatus, e);
        }
    }

    private PaymentStatus failPayment(String portoneId, Long orderId) {
        transactionOperations.executeWithoutResult(status -> {
            paymentService.fail(portoneId);
            orderCommandService.failPaymentIfPossible(orderId);
        });
        createPaymentCard(orderId, PaymentStatus.FAILED, null, null, null);
        return PaymentStatus.FAILED;
    }


}