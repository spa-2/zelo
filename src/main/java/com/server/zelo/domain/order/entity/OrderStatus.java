package com.server.zelo.domain.order.entity;

public enum OrderStatus {
    // 주문 생성 완료, 결제 대기 또는 진행 중
    PAYMENT_PENDING{
        @Override
        public boolean isTransitionAllowed(OrderStatus nextStatus) {
            return nextStatus == PAID || nextStatus == PAYMENT_FAILED || nextStatus == CANCELED;
        }
    },

    // 결제 완료, 재고 차감 확정
    PAID{
        @Override
        public boolean isTransitionAllowed(OrderStatus nextStatus) {
            return nextStatus == CANCELED;
        }
    },

    // 결제 실패 확정, 선점 재고 해제
    PAYMENT_FAILED{
        @Override
        public boolean isTransitionAllowed(OrderStatus nextStatus) {
            return false;
        }
    },

    // 결제 후 전액 환불 또는 결제 확정 전 자동 취소
    CANCELED{
        @Override
        public boolean isTransitionAllowed(OrderStatus nextStatus) {
            return false;
        }
    };

    public abstract boolean isTransitionAllowed(OrderStatus nextStatus);
}

