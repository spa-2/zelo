package com.server.zelo.domain.order.mock.dto;

// TODO(order): 주문 도메인이 머지되면 Order 엔티티로 교체하고 이 record는 삭제한다
public record MockOrder(
        Long orderId,
        Long userId,
        String orderName,
        Long totalAmount,
        boolean paymentPending
) {

    public static MockOrder of(Long orderId, Long userId, String orderName, Long totalAmount, boolean paymentPending) {
        return new MockOrder(orderId, userId, orderName, totalAmount, paymentPending);
    }
}