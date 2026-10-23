package com.server.zelo.domain.order.dto.response;

import com.server.zelo.domain.quote.model.Quote;

import java.time.LocalDateTime;

public record CreateOrderConfirmationResponse(
        String confirmationId,
        Long roomId,
        String quoteId,
        Long totalAmount,
        String addressSummary,
        LocalDateTime expiresAt
) {
    public static CreateOrderConfirmationResponse of(
            String confirmationId,
            Quote quote,
            String addressSummary,
            LocalDateTime expiresAt
    ) {
        return new CreateOrderConfirmationResponse(
                confirmationId,
                quote.roomId(),
                quote.quoteId(),
                quote.totalAmount(),
                addressSummary,
                expiresAt
        );
    }
}
