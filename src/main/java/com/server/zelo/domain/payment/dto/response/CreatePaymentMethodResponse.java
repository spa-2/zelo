package com.server.zelo.domain.payment.dto.response;

import com.server.zelo.domain.payment.entity.PaymentMethod;
import com.server.zelo.domain.payment.entity.PaymentMethodType;

public record CreatePaymentMethodResponse(
        Long paymentMethodId,
        PaymentMethodType type,
        String displayName
) {

    public static CreatePaymentMethodResponse from(PaymentMethod paymentMethod) {
        return new CreatePaymentMethodResponse(
                paymentMethod.getId(), paymentMethod.getType(), paymentMethod.getDisplayName());
    }
}