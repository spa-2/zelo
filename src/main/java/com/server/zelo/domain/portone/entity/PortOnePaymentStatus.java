package com.server.zelo.domain.portone.entity;

import java.util.Arrays;

// 포트원 V2 결제 단건 조회 응답의 status 값. 실제 연동 시 포트원 문서와 대조한다
public enum PortOnePaymentStatus {
    READY,
    PAY_PENDING,
    PAID,
    FAILED,
    PARTIAL_CANCELLED,
    CANCELLED,
    UNKNOWN;

    public static PortOnePaymentStatus from(String value) {
        return Arrays.stream(values())
                .filter(status -> status.name().equals(value))
                .findFirst()
                .orElse(UNKNOWN);
    }
}