package com.server.zelo.domain.payment.dto.response;

import com.server.zelo.domain.payment.entity.Payment;

public record PaymentFindTarget(Long paymentId, String portoneId) {

    public static PaymentFindTarget from(Payment payment) {
        return new PaymentFindTarget(payment.getId(), payment.getPortoneId());
    }
}