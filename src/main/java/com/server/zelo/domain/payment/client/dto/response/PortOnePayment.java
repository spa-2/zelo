package com.server.zelo.domain.payment.client.dto.response;

import com.server.zelo.domain.payment.client.PortOnePaymentStatus;

import java.time.LocalDateTime;

public record PortOnePayment(
        String portoneId,
        PortOnePaymentStatus status,
        Long amount,
        LocalDateTime paidAt
) {

    public static PortOnePayment of(String portoneId, PortOnePaymentStatus status, Long amount, LocalDateTime paidAt) {
        return new PortOnePayment(portoneId, status, amount, paidAt);
    }

    public boolean isPaid() {
        return status == PortOnePaymentStatus.PAID;
    }

    public boolean isFailed() {
        return status == PortOnePaymentStatus.FAILED || status == PortOnePaymentStatus.CANCELLED;
    }
}