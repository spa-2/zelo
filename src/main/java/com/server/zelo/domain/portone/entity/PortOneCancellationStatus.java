package com.server.zelo.domain.portone.entity;

import java.util.Arrays;

// 포트원 V2 결제 취소 응답의 cancellation.status 값
public enum PortOneCancellationStatus {
    REQUESTED,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    public static PortOneCancellationStatus from(String value) {
        return Arrays.stream(values())
                .filter(status -> status.name().equals(value))
                .findFirst()
                .orElse(UNKNOWN);
    }
}