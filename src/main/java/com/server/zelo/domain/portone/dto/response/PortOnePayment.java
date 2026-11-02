package com.server.zelo.domain.portone.dto.response;

import com.server.zelo.domain.portone.entity.PortOneCancellationStatus;
import com.server.zelo.domain.portone.entity.PortOnePaymentStatus;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public record PortOnePayment(
        String portoneId,
        PortOnePaymentStatus status,
        Long amount,
        LocalDateTime paidAt,
        String methodName,
        String failureReason,
        List<PortOneCancellation> cancellations
) {

    public PortOnePayment(String portoneId, PortOnePaymentStatus status, Long amount, LocalDateTime paidAt,
                          String methodName, String failureReason) {
        this(portoneId, status, amount, paidAt, methodName, failureReason, List.of());
    }

    public static PortOnePayment of(String portoneId, PortOnePaymentStatus status, Long amount, LocalDateTime paidAt) {
        return of(portoneId, status, amount, paidAt, PortOnePaymentResponse.ETC);
    }

    public static PortOnePayment of(String portoneId, PortOnePaymentStatus status, Long amount,
                                    LocalDateTime paidAt, String methodName) {
        return new PortOnePayment(portoneId, status, amount, paidAt, methodName, null);
    }

    public static PortOnePayment from(PortOnePaymentResponse response, ZoneId zoneId) {
        Long amount = response.amount() == null ? null : response.amount().total();
        LocalDateTime paidAt = response.paidAt() == null
                ? null
                : response.paidAt().atZoneSameInstant(zoneId).toLocalDateTime();
        List<PortOneCancellation> cancellations = response.cancellations() == null
                ? List.of()
                : response.cancellations().stream()
                        .map(cancellation -> new PortOneCancellation(
                                cancellation.id(),
                                PortOneCancellationStatus.from(cancellation.status()),
                                cancellation.totalAmount(),
                                cancellation.requestedAt() == null
                                        ? null
                                        : cancellation.requestedAt().atZoneSameInstant(zoneId).toLocalDateTime()))
                        .toList();
        return new PortOnePayment(response.id(), PortOnePaymentStatus.from(response.status()), amount, paidAt,
                response.methodName(), response.failureReason(), cancellations);
    }

    // 환불 요청 뒤에 같은 금액으로 들어온 취소 중 가장 최근 것. 이전 환불의 취소는 요청 시각이 더 빨라 걸러진다
    public Optional<PortOneCancellation> findCancellation(Long amount, LocalDateTime requestedSince) {
        return cancellations.stream()
                .filter(cancellation -> amount.equals(cancellation.amount()))
                .filter(cancellation -> cancellation.requestedAt() != null
                        && !cancellation.requestedAt().isBefore(requestedSince))
                .max(Comparator.comparing(PortOneCancellation::requestedAt));
    }

    public boolean isPaid() {return status == PortOnePaymentStatus.PAID;}

    public boolean isFailed() {return status == PortOnePaymentStatus.FAILED;}

    public boolean isCancelled() {return status == PortOnePaymentStatus.CANCELLED;}

    public boolean isPartiallyCancelled() {return status == PortOnePaymentStatus.PARTIAL_CANCELLED;}
}
