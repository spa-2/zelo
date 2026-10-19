package com.server.zelo.domain.order.mock.service;

import com.server.zelo.domain.order.mock.dto.MockOrder;
import org.springframework.stereotype.Service;

// TODO(order): 주문 도메인이 머지되면 OrderService로 교체하고 이 클래스는 삭제한다.
//  주문 담당자에게 대응하는 메서드(본인 주문 여부, PAYMENT_PENDING 여부, total_amount, order_name) 확인 요청
@Service
public class MockOrderService {

    // 로컬에서 API를 호출해볼 수 있도록 어떤 주문 ID든 1번 회원의 결제 대기 주문으로 돌려준다
    private static final Long MOCK_USER_ID = 1L;
    private static final Long MOCK_TOTAL_AMOUNT = 32_000L;
    private static final String MOCK_ORDER_NAME = "김치볶음밥 재료";

    public MockOrder getOrder(Long orderId) {
        return MockOrder.of(orderId, MOCK_USER_ID, MOCK_ORDER_NAME, MOCK_TOTAL_AMOUNT, true);
    }
}