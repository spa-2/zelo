package com.server.zelo.domain.refund.dto.response;

public record OrderRefundAmount(
        Long orderId,
        Long refundedAmount
) {
}