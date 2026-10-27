package com.server.zelo.domain.payment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentMethodType {

    CARD("카드"),
    ACCOUNT("계좌");

    private final String description;
}