package com.server.zelo.domain.refund.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RefundType {

    FULL("전액 환불"),
    PARTIAL("부분 환불");

    private final String description;
}