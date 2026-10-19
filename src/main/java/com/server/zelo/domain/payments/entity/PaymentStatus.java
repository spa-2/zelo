package com.server.zelo.domain.payments.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {

    READY("결제 시작"),
    CONFIRM_PENDING("결제 확인 대기"),
    PAID("결제 완료"),
    FAILED("결제 실패"),
    CANCELED("결제 취소");

    private final String description;
}