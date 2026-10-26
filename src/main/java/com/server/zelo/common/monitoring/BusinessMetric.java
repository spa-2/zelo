package com.server.zelo.common.monitoring;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 비즈니스 지표 이름은 요기 한곳에서만 정함. 파트마다 이름이 제각각이면 대시보드에서 못 묶음
// 태그 값은 사유 코드처럼 개수가 정해진 값만 씀 (userId, orderId 같은 값을 넣으면 지표가 끝없이 늘어남)
@Getter
@RequiredArgsConstructor
public enum BusinessMetric {

    //TODO 이름만 미리 만들어 둔거니 각자 가져가서 붙히시면 됩니다~!

    // 견적
    QUOTE_CREATED("zelo.quote.created", "견적 발급 수"),
    QUOTE_EXCLUDED("zelo.quote.excluded", "견적에서 빠진 품목 수 (reason)"),
    QUOTE_CREATE_TIME("zelo.quote.create.time", "견적 발급 시간"),

    // 주문
    ORDER_CREATED("zelo.order.created", "주문 생성 수"),
    ORDER_REJECTED("zelo.order.rejected", "주문 거부 수 (reason)"),

    // 결제
    PAYMENT_RESULT("zelo.payment.result", "결제 결과 수 (status)");

    private final String key;
    private final String description;
}