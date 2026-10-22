package com.server.zelo.domain.order.dto.response;

import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;

import java.time.LocalDateTime;

public record GetOrderSummaryResponse(
        Long orderId, OrderStatus status, String orderName,
        Long totalAmount, LocalDateTime createdAt) {

    public static GetOrderSummaryResponse from(Order order) {
        return  new GetOrderSummaryResponse(
                order.getId(),
                order.getStatus(),
                order.getOrderName(),
                order.getTotalAmount(),
                order.getCreatedAt()
        );
    }
}
