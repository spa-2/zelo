package com.server.zelo.domain.payment.dto.response;

import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;

public record CreatePaymentResponse(
        Long paymentId,
        Long orderId,
        String portoneId,
        String orderName,
        Long amount,
        PaymentStatus status
) {

    public static CreatePaymentResponse from(Payment payment, String orderName) {
        return new CreatePaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getPortoneId(),
                orderName,
                payment.getAmount(),
                payment.getStatus()
        );
    }
}