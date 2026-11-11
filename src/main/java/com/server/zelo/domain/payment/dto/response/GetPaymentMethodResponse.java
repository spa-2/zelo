package com.server.zelo.domain.payment.dto.response;

import com.server.zelo.domain.payment.entity.PaymentMethod;
import com.server.zelo.domain.payment.entity.PaymentMethodType;

public record GetPaymentMethodResponse(
        Long paymentMethodId,
        PaymentMethodType type,
        String displayName
) {

    public static GetPaymentMethodResponse from(PaymentMethod paymentMethod) {
        return new GetPaymentMethodResponse(
                paymentMethod.getId(), paymentMethod.getType(), paymentMethod.getDisplayName());
    }
}