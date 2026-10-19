package com.server.zelo.domain.order.mock.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

// TODO(order): 주문 도메인이 머지되면 OrderService(또는 주문 취소 Command)로 교체하고 이 클래스는 삭제한다
@Slf4j
@Service
@Profile({"local", "dev"})
public class MockOrderService {

    // TODO(order): 주문을 PAID로 변경
    public void completePayment(Long orderId) {
        log.info("[MOCK] 주문 결제 완료 처리 orderId={}", orderId);
    }

    // TODO(order): 주문 취소 + 재고 복구 (재고 복구는 주문 취소 한 곳에서만)
    public void cancel(Long orderId) {
        log.info("[MOCK] 주문 취소 및 재고 복구 orderId={}", orderId);
    }
}