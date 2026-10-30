package com.server.zelo.domain.portone.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOnePaymentResponse(
        String id,
        String status,
        Amount amount,
        OffsetDateTime paidAt,
        Method method,
        Failure failure
) {
    public static final String ETC = "ETC";

    public String methodName() {
        if (method == null) {
            return ETC;
        }
        if (method.card() != null && method.card().name() != null) {
            return method.card().name();
        }
        if (method.provider() != null) {
            return method.provider();
        }
        if (method.bank() != null) {
            return method.bank();
        }
        return ETC;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(Long total) {
    }

    // 포트원은 결제 수단 종류마다 다른 필드를 채운다. 이름을 만드는 데 쓰는 필드만 받는다
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Method(Card card, String provider, String bank) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Card(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Failure(String reason, String pgMessage) {
    }

    public String failureReason() {
        if (failure == null) {
            return null;
        }
        if (failure.pgMessage() != null && !failure.pgMessage().isBlank()) {
            return failure.pgMessage();
        }
        return failure.reason() == null || failure.reason().isBlank() ? null : failure.reason();
    }
}