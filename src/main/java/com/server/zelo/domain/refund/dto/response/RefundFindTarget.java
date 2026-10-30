package com.server.zelo.domain.refund.dto.response;

public record RefundFindTarget(
        Long refundId,
        Long orderId,
        String portoneId
) {
}