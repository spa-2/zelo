package com.server.zelo.domain.order.dto.response;

import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderStatus;

public record CancelOrderResponse(Long orderId, OrderStatus orderStatus) {

    public static CancelOrderResponse from(Order order) {
        return new CancelOrderResponse(order.getId(), order.getStatus());
    }
}