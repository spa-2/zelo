package com.server.zelo.domain.order.dto.response;

import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;

import java.time.LocalDateTime;

public record GetOrderSummaryResponse(
        Long orderId, OrderStatus status, String orderName,
        Long totalAmount, Long paidAmount, LocalDateTime createdAt) {

    public static GetOrderSummaryResponse from(Order order, Long refundedAmount) {
        return new GetOrderSummaryResponse(
                order.getId(),
                order.getStatus(),
                order.getOrderName(),
                order.getTotalAmount(),
                paidAmount(order, refundedAmount),
                order.getCreatedAt()
        );
    }

    // 결제된 적 없는 주문(결제 대기·실패, 결제 전 취소)은 보여 줄 결제 금액이 없어 null로 둔다
   static Long paidAmount(Order order, Long refundedAmount) {
        boolean paid = order.getStatus() == OrderStatus.PAID
                || (order.getStatus() == OrderStatus.CANCELED && refundedAmount > 0);
        return paid ? order.getTotalAmount() - refundedAmount : null;
    }
}