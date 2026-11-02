package com.server.zelo.domain.order.entity;

public enum OrderAuditAction {
    CREATED,
    PAID,
    PAYMENT_FAILED,
    CANCELED,
    REQUEST_REJECTED // 주문이 생성되지 않았거나 상태가 바뀌지 않은 거부 요청
}
