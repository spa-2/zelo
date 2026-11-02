package com.server.zelo.domain.order.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.order.dto.request.CreateOrderRequest;
import com.server.zelo.domain.order.dto.response.CancelOrderResponse;
import com.server.zelo.domain.order.model.CreateOrderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderFacade {
    private final OrderCommandService orderCommandService;
    private final LockDistributeService lockDistributeService;
    private final OrderAuditService orderAuditService;

    // Command의 트랜잭션이 커밋될 때까지 사용자별 주문 생성 락을 유지
    public CreateOrderResult createOrder(Long userId, CreateOrderRequest request) {
        try {
            return lockDistributeService.execute(
                    LockKey.createOrder(userId),
                    () -> orderCommandService.createOrder(userId, request)
            );
        } catch (BusinessException exception) {
            // 주문 트랜잭션은 이미 롤백됐으므로 거부 기록은 따로 남김
            recordRejection(userId, request.quoteId(), exception);
            throw exception;
        }
    }

    // 기록에 실패해도 사용자에게는 원래 오류를 그대로 돌려줌
    private void recordRejection(Long userId, String quoteId, BusinessException exception) {
        try {
            orderAuditService.recordRejection(userId, quoteId, exception);
        } catch (RuntimeException auditException) {
            log.warn("주문 거부 감사 로그 저장 실패 userId={} quoteId={}", userId, quoteId, auditException);
        }
    }

    public CancelOrderResponse cancelBeforePayment(Long userId, Long orderId, String reason) {
        Optional<String> portoneId = orderCommandService.findPaymentIdForCancellation(userId, orderId);

        // 결제 정보가 없는 기존 주문은 Command의 주문 행 잠금으로 취소
        if (portoneId.isEmpty()) {
            return orderCommandService.cancelBeforePayment(userId, orderId, reason);
        }

        // 결제 확인과 같은 락을 사용하고, 취소 트랜잭션이 끝날 때까지 유지
        return lockDistributeService.execute(
                LockKey.confirmPayment(portoneId.get()),
                () -> orderCommandService.cancelBeforePayment(userId, orderId, reason)
        );
    }

    public boolean cancelExpiredOrder(Long orderId, LocalDateTime cutoff) {
        Optional<String> portoneId = orderCommandService.findPortOneIdForExpiration(orderId);

        // 결제 기록이 없는 주문은 Command의 주문 행 잠금으로 보호
        if (portoneId.isEmpty()) {
            return orderCommandService.cancelExpiredOrder(orderId, cutoff);
        }

        // 결제 확인과 같은 락을 사용하고, 취소 트랜잭션 커밋까지 유지
        return lockDistributeService.execute(
                LockKey.confirmPayment(portoneId.get()),
                () -> orderCommandService.cancelExpiredOrder(orderId, cutoff)
        );
    }
}