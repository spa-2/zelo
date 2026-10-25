package com.server.zelo.domain.chat.dto.response;

import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.payment.entity.PaymentStatus;

import java.time.LocalDateTime;
import java.util.List;

// 결제 결과를 채팅방에 카드로 남겨, 방을 다시 열어도 그때 결제 정보가 그대로 보인다
public record PaymentCardPayload(
        String type,
        Long orderId,
        String orderName,
        List<Item> items,
        Long paidAmount,
        PaymentStatus status,
        LocalDateTime paidAt
) {

    public static final String TYPE = "PAYMENT_CARD";

    public static PaymentCardPayload from(Order order, Long paidAmount, LocalDateTime paidAt) {
        List<Item> items = order.getOrderItems().stream()
                .map(orderItem -> new Item(orderItem.getProductName(), orderItem.getProductUnit(),
                        orderItem.getQuantity(), orderItem.getLineAmount()))
                .toList();
        return new PaymentCardPayload(TYPE, order.getId(), order.getOrderName(), items,
                paidAmount, PaymentStatus.PAID, paidAt);
    }

    public record Item(
            String productName,
            String productUnit,
            Integer quantity,
            Long lineAmount
    ) {
    }
}