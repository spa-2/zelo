package com.server.zelo.domain.order.service;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.order.dto.request.CreateOrderRequest;
import com.server.zelo.domain.order.dto.response.CancelOrderResponse;
import com.server.zelo.domain.order.model.CreateOrderResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OrderFacade {
    private final OrderCommandService orderCommandService;
    private final LockDistributeService lockDistributeService;

    // Command의 트랜잭션이 커밋될 때까지 사용자별 주문 생성 락을 유지
    public CreateOrderResult createOrder(Long userId, CreateOrderRequest request) {
        return lockDistributeService.execute(
                LockKey.createOrder(userId),
                () -> orderCommandService.createOrder(userId, request)
        );
    }

    public CancelOrderResponse cancelBeforePayment(Long userId, Long orderId) {
        Optional<String> portoneId = orderCommandService.findPaymentIdForCancellation(userId, orderId);

        // 결제 정보가 없는 기존 주문은 Command의 주문 행 잠금으로 취소
        if (portoneId.isEmpty()) {
            return orderCommandService.cancelBeforePayment(userId, orderId);
        }

        // 결제 확인과 같은 락을 사용하고, 취소 트랜잭션이 끝날 때까지 유지
        return lockDistributeService.execute(
                LockKey.confirmPayment(portoneId.get()),
                () -> orderCommandService.cancelBeforePayment(userId, orderId)
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