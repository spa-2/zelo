package com.server.zelo.domain.portone.dto.response;

import com.server.zelo.domain.portone.entity.PortOneCancellationStatus;

import java.time.LocalDateTime;

// 포트원 결제 조회 응답의 취소 내역 한 건
public record PortOneCancellation(
        String id,
        PortOneCancellationStatus status,
        Long amount,
        LocalDateTime requestedAt
) {
}
