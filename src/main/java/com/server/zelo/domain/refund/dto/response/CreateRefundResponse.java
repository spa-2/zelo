package com.server.zelo.domain.refund.dto.response;

import com.server.zelo.domain.refund.entity.RefundStatus;

// 프론트는 message를 그대로 띄운다. REQUESTED면 포트원 결과를 기다리는 중이라 웹훅으로 마저 반영된다
public record CreateRefundResponse(
        Long refundId,
        RefundStatus status,
        String message
) {

    public static CreateRefundResponse of(Long refundId, RefundStatus status) {
        return new CreateRefundResponse(refundId, status, messageOf(status));
    }

    private static String messageOf(RefundStatus status) {
        return switch (status) {
            case COMPLETED -> "환불이 완료됐습니다.";
            case FAILED -> "환불에 실패했어요. 잠시 후 다시 시도해 주세요.";
            case REQUESTED -> "환불을 처리하고 있어요.";
        };
    }
}