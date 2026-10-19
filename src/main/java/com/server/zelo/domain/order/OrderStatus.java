package com.server.zelo.domain.order;

public enum OrderStatus {
    PAYMENT_PENDING, // 주문 생성 완료, 결제 대기 또는 진행 중
    PAID,            // 결제 완료, 재고 차감 확정
    PAYMENT_FAILED,  // 결제 실패 확정, 선점 재고 해제
    CANCELED         // 전액 환불 완료에 따른 주문 취소
}

