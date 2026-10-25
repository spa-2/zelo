package com.server.zelo.domain.order.dto.response;

import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.OrderItem;
import com.server.zelo.domain.order.entity.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

public record CreateOrderResponse(
        Long orderId,
        String quoteId,
        String orderName,
        ActorType actorType,
        Long chatRoomId,
        OrderStatus status,
        List<Items> items,
        Long totalAmount,
        String portoneId,
        LocalDateTime createdAt
) {
    public static CreateOrderResponse from(Order order, List<OrderItem> orderItems, String portoneId) {
        return new CreateOrderResponse(
                order.getId(),
                order.getQuoteId(),
                order.getOrderName(),
                order.getRequesterType(),
                order.getChatRoom().getId(),
                order.getStatus(),
                orderItems.stream().map(Items::from).toList(),
                order.getTotalAmount(),
                portoneId,
                order.getCreatedAt()
        );
    }

    public record Items(
            Long productId,
            String productName,
            String productUnit,
            Integer quantity,
            Long unitPrice,
            Long lineAmount
    ) {
        public static Items from(OrderItem orderItem) {
            return new Items(
                    orderItem.getProduct().getId(),
                    orderItem.getProductName(),
                    orderItem.getProductUnit(),
                    orderItem.getQuantity(),
                    orderItem.getUnitPrice(),
                    orderItem.getLineAmount()
            );
        }
    }
}
