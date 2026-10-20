package com.server.zelo.domain.payment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {

    READY("결제 시작") {
        @Override
        public boolean canTransitTo(PaymentStatus target) {
            return target == CONFIRM_PENDING || target == FAILED;
        }
    },
    CONFIRM_PENDING("결제 확인 대기") {
        @Override
        public boolean canTransitTo(PaymentStatus target) {
            return target == PAID || target == FAILED || target == CANCELED;
        }
    },
    PAID("결제 완료") {
        @Override
        public boolean canTransitTo(PaymentStatus target) {
            return target == CANCELED;
        }
    },
    FAILED("결제 실패") {
        @Override
        public boolean canTransitTo(PaymentStatus target) {
            return false;
        }
    },
    CANCELED("결제 취소") {
        @Override
        public boolean canTransitTo(PaymentStatus target) {
            return false;
        }
    };

    private final String description;

    public abstract boolean canTransitTo(PaymentStatus target);
}