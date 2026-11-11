package com.server.zelo.domain.order.model;

import com.server.zelo.domain.order.dto.response.CreateOrderResponse;

// HTTP 상태를 결정할 신규 생성 여부와 응답 데이터를 함께 전달
public record CreateOrderResult(
        CreateOrderResponse response,
        boolean created
) {
}