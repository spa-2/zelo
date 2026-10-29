package com.server.zelo.domain.payment.dto.response;

import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;

public record RetryPaymentResponse(
        Long orderId,
        String portoneId,
        PaymentStatus status,
        String orderName,
        Long totalAmount
) {
    public static RetryPaymentResponse from(Order order, Payment payment) {
        return new RetryPaymentResponse(order.getId(), payment.getPortoneId(), payment.getStatus(),
                order.getOrderName(), order.getTotalAmount());
    }
}