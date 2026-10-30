package com.server.zelo.domain.refund.dto.response;

public record RefundFindTarget(
        Long orderId,
        String portoneId
) {
}
