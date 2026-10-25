package com.server.zelo.domain.chat.dto.response;

import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.payment.entity.PaymentStatus;

import java.time.LocalDateTime;
import java.util.List;

// 결제 결과를 채팅방에 카드로 남겨, 방을 다시 열어도 그때 결제 정보가 그대로 보인다
// status: PAID, FAILED, CANCELED / reason: CANCELED일 때만 취소된 까닭 (USER_CANCELED, AMOUNT_MISMATCH, ORDER_CLOSED)
// paidAmount: 결제 성공이면 결제 금액, 환불이면 결제됐다가 돌려준 금액, 돈이 나가지 않았으면 null
public record PaymentCardPayload(
        String type,
        Long orderId,
        String orderName,
        List<Item> items,
        Long paidAmount,
        PaymentStatus status,
        String reason,
        LocalDateTime paidAt
) {

    public static final String TYPE = "PAYMENT_CARD";
    // CANCELED 하나로는 사용자에게 다르게 알려야 할 세 경우가 구분되지 않아 사유를 따로 둔다
    public static final String USER_CANCELED = "USER_CANCELED";
    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    // 결제가 들어왔을 때 주문이 결제 대기가 아니던 경우. 취소뿐 아니라 이미 결제됨·결제 실패도 포함한다
    public static final String ORDER_CLOSED = "ORDER_CLOSED";

    public static PaymentCardPayload from(Order order, PaymentStatus status, String reason,
                                          Long paidAmount, LocalDateTime paidAt) {
        List<Item> items = order.getOrderItems().stream()
                .map(orderItem -> new Item(orderItem.getProductName(), orderItem.getProductUnit(),
                        orderItem.getQuantity(), orderItem.getLineAmount()))
                .toList();
        return new PaymentCardPayload(TYPE, order.getId(), order.getOrderName(), items,
                paidAmount, status, reason, paidAt);
    }

    public record Item(
            String productName,
            String productUnit,
            Integer quantity,
            Long lineAmount
    ) {
    }
}
