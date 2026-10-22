package com.server.zelo.domain.payment.dto.response;

import com.server.zelo.domain.payment.entity.PaymentStatus;

// 프론트는 message를 그대로 채팅에 띄운다. CONFIRM_PENDING이면 아직 포트원에서 결제가 끝나지 않아 웹훅으로 마저 반영된다
public record PaymentConfirmResponse(
        String portoneId,
        PaymentStatus status,
        String message
) {

    public static PaymentConfirmResponse of(String portoneId, PaymentStatus status) {
        return new PaymentConfirmResponse(portoneId, status, messageOf(status));
    }

    private static String messageOf(PaymentStatus status) {
        return switch (status) {
            case PAID -> "결제가 완료됐습니다.";
            // MVP는 재결제를 허용하지 않아 실패한 주문은 취소되므로 다시 주문하도록 안내한다
            case FAILED -> "결제에 실패했어요. 주문을 다시 진행해 주시길 바랍니다.";
            case CANCELED -> "결제 금액이 주문 금액과 달라 결제를 취소했어요.";
            case READY, CONFIRM_PENDING -> "결제를 확인하고 있어요.";
        };
    }
}