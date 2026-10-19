package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.mock.dto.MockOrder;
import com.server.zelo.domain.order.mock.service.MockOrderService;
import com.server.zelo.domain.payment.dto.response.CreatePaymentResponse;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.order.mock.dto.MockOrder;
import com.server.zelo.domain.order.mock.service.MockOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PaymentCommandService {

    private final PaymentService paymentService;
    private final MockOrderService mockOrderService;

    @Transactional
    public CreatePaymentResponse createPayment(Long userId, Long orderId) {
        // TODO(order): 주문 도메인 머지 후 OrderService 조회로 교체하고, 아래 검증은 Order 엔티티로 옮긴다
        MockOrder order = mockOrderService.getOrder(orderId);
        validateOwner(order, userId);
        validatePayable(order);

        // 결제창을 닫고 다시 누르는 경우 결제 기록이 쌓이지 않도록 진행 중인 READY 결제를 재사용한다
        Payment payment = paymentService.findReadyPayment(orderId)
                .orElseGet(() -> paymentService.createPayment(orderId, order.totalAmount()));

        return CreatePaymentResponse.from(payment, order.orderName());
    }

    private void validateOwner(MockOrder order, Long userId) {
        if (!Objects.equals(order.userId(), userId)) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }
    }

    private void validatePayable(MockOrder order) {
        if (!order.paymentPending()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
        }
    }
}
