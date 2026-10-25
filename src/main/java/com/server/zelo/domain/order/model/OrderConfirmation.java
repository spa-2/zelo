package com.server.zelo.domain.order.model;

public record OrderConfirmation(
        Long userId,
        Long roomId,
        String quoteId
) {
}
