package com.server.zelo.domain.order.entity;

public enum StockState {
    RESERVED, // 주문 생성 시 선점
    DEDUCTED, // 결제 성공. 실재고 차감 확정
    RELEASED, // 거절, 만료, 결제 실패. 선점만 해제
    RESTORED // 환불. 차감했던 실재고 복원
}
