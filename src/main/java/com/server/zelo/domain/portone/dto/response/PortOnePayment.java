package com.server.zelo.domain.portone.dto.response;

import com.server.zelo.domain.portone.entity.PortOnePaymentStatus;

import java.time.LocalDateTime;
import java.time.ZoneId;

public record PortOnePayment(
        String portoneId,
        PortOnePaymentStatus status,
        Long amount,
        LocalDateTime paidAt
) {

    public static PortOnePayment of(String portoneId, PortOnePaymentStatus status, Long amount, LocalDateTime paidAt) {
        return new PortOnePayment(portoneId, status, amount, paidAt);
    }

    public static PortOnePayment from(PortOnePaymentResponse response, ZoneId zoneId) {
        Long amount = response.amount() == null ? null : response.amount().total();
        LocalDateTime paidAt = response.paidAt() == null
                ? null
                : response.paidAt().atZoneSameInstant(zoneId).toLocalDateTime();
        return of(response.id(), PortOnePaymentStatus.from(response.status()), amount, paidAt);
    }

    public boolean isPaid() {
        return status == PortOnePaymentStatus.PAID;
    }

    public boolean isFailed() {
        return status == PortOnePaymentStatus.FAILED || status == PortOnePaymentStatus.CANCELLED;
    }
}