package com.server.zelo.domain.order.entity;

public enum StockState {
    // 주문 생성 시 선점
    RESERVED{
        @Override
        public boolean isTransitionAllowed(StockState nextState) {
            return nextState == DEDUCTED || nextState == RELEASED;
        }
    },

    // 결제 성공. 실재고 차감 확정
    DEDUCTED{
        @Override
        public boolean isTransitionAllowed(StockState nextState) {
            return nextState == RESTORED;
        }
    },

    // 결제 실패·만료·차감 전 취소. 선점만 해제
    RELEASED{
        @Override
        public boolean isTransitionAllowed(StockState nextState) {
            return false;
        }
    },

    // 환불. 차감했던 실재고 복원
    RESTORED{
        @Override
        public boolean isTransitionAllowed(StockState nextState) {
            return false;
        }
    };

    public abstract boolean isTransitionAllowed(StockState nextState);
}
