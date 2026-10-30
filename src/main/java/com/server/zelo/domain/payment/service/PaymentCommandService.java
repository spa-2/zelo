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
import com.server.zelo.domain.payment.dto.response.RetryPaymentResponse;
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
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCommandService {

    private static final String AMOUNT_MISMATCH_REASON = "결제 금액 불일치";
    private static final String CLOSED_ORDER_REASON = "결제 대기 중이 아닌 주문";
    private static final String LATE_PAID_REASON = "실패 처리 후 늦게 승인된 결제";

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
        if (payment.isPaid()) {
            return PaymentStatus.PAID;
        }
        if (payment.isConfirmed()) {
            return refundIfLatePaid(payment);
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
            return failPayment(portoneId, orderId, pgPayment.failureReason());
        }

        // 결제창을 닫아도 같은 주문으로 재결제할 수 있도록 결제만 취소하고 주문과 재고 선점은 그대로 둔다
        if (pgPayment.isCancelled()) {
            transactionOperations.executeWithoutResult(status -> paymentService.cancel(portoneId, pgPayment.amount()));
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
                return failPayment(portoneId, orderId, (String) e.getData());
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

    // paidAmount는 실제로 돈이 나간 경우(결제 완료, 환불)에만 넘긴다
    private void createPaymentCard(Long orderId, PaymentStatus paymentStatus, String reason,
                                   Long paidAmount, LocalDateTime paidAt) {
        createPaymentCard(orderId, paymentStatus, reason, paidAmount, paidAt, null);
    }

    // paidAmount는 실제로 돈이 나간 경우(결제 완료, 환불)에만 넘긴다
    private void createPaymentCard(Long orderId, PaymentStatus paymentStatus, String reason,
                                   Long paidAmount, LocalDateTime paidAt, String failureReason) {
        try {
            transactionOperations.executeWithoutResult(status -> {
                Order order = orderService.getOrderWithItems(orderId);
                if (order.getChatRoom() == null) {
                    return;
                }
                chatMessageService.createPaymentCardMessage(order.getChatRoom().getId(), order.getUser().getId(),
                        PaymentCardPayload.from(order, paymentStatus, reason, paidAmount, paidAt, failureReason));
            });
        } catch (RuntimeException e) {
            log.error("결제 결과 카드 저장 실패 orderId={}, status={}", orderId, paymentStatus, e);
        }
    }

    // 확인 대기 결제는 재결제 전에 결제 확인 락을 걸고 다시 조회해야 해서 결제 ID를 먼저 꺼낸다
    public Optional<String> findConfirmPendingPortoneId(Long userId, Long orderId) {
        orderService.validateOwner(orderId, userId);
        return paymentService.findLatestPayment(orderId)
                .filter(Payment::isConfirmPending)
                .map(Payment::getPortoneId);
    }

    // 실패 원인은 다양해서 원인 대신 최신 결제 상태로 다시 결제할 결제를 정한다
    public RetryPaymentResponse retryPayment(Long userId, Long orderId) {
        return transactionOperations.execute(status -> {
            Order order = orderService.getOrderWithItemsForUpdate(orderId);
            if (!userId.equals(order.getUser().getId())) {
                throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
            }
            Payment latest = paymentService.getLatestPayment(orderId);
            if (latest.isPaid()) {
                return RetryPaymentResponse.from(order, latest);
            }
            if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
                throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
            }
            // 다시 조회해도 결과를 모르면 이미 결제됐을 수 있어 새 결제를 만들지 않는다
            if (latest.isConfirmPending()) {
                throw new BusinessException(ErrorCode.PAYMENT_CONFIRM_PENDING);
            }
            if (order.isPaymentExpired(LocalDateTime.now())) {
                throw new BusinessException(ErrorCode.ORDER_PAYMENT_EXPIRED);
            }
            if (latest.isReady()) {
                return RetryPaymentResponse.from(order, latest);
            }
            return RetryPaymentResponse.from(order, paymentService.createPayment(orderId, order.getTotalAmount()));
        });
    }

    // 실패·결제창 닫음으로 끝난 결제가 포트원에서 늦게 승인되면 재결제와 겹쳐 돈이 두 번 나갈 수 있어 돌려준다
    private PaymentStatus refundIfLatePaid(Payment payment) {
        String portoneId = payment.getPortoneId();
        PortOnePayment pgPayment = portOneClient.getPayment(portoneId);
        if (!pgPayment.isPaid()) {
            return payment.getStatus();
        }
        log.warn("실패 처리 후 늦게 승인된 결제 환불 portoneId={}, orderId={}", portoneId, payment.getOrderId());
        portOneClient.cancelPayment(portoneId, LATE_PAID_REASON);
        transactionOperations.executeWithoutResult(status -> paymentService.cancelLatePaid(portoneId, pgPayment.amount()));
        createPaymentCard(payment.getOrderId(), PaymentStatus.CANCELED, PaymentCardPayload.LATE_PAID, pgPayment.amount(), null);
        return PaymentStatus.CANCELED;
    }

    private PaymentStatus failPayment(String portoneId, Long orderId, String failureReason) {
        transactionOperations.executeWithoutResult(status -> paymentService.fail(portoneId, failureReason));
        createPaymentCard(orderId, PaymentStatus.FAILED, null, null, null, failureReason);
        return PaymentStatus.FAILED;
    }
}