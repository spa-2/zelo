package com.server.zelo.domain.order.service;

import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.order.dto.request.CreateOrderRequest;
import com.server.zelo.domain.order.model.CreateOrderResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
}