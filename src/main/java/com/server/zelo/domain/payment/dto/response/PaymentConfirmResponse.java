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
            // 실패해도 주문은 결제 대기로 남아 같은 주문으로 재결제할 수 있으므로 결제 수단을 다시 고르도록 안내한다
            case FAILED -> "결제에 실패했어요. 결제 수단을 다시 골라 주세요.";
            // 결제창 닫음·금액 불일치·닫힌 주문 등 취소 사유가 여러 가지라 공통 문구로 안내한다
            case CANCELED -> "결제가 취소됐어요.";
            case READY, CONFIRM_PENDING -> "결제를 확인하고 있어요.";
        };
    }
}