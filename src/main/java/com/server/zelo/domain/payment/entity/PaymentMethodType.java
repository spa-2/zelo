package com.server.zelo.domain.payment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 계좌 등록은 PG 계약이 필요해 지금은 카드만 받는다
@Getter
@RequiredArgsConstructor
public enum PaymentMethodType {

    CARD("카드");

    private final String description;
}