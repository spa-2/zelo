package com.server.zelo.domain.refund.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RefundStatus {

    REQUESTED("환불 요청") {
        @Override
        public boolean canTransitTo(RefundStatus target) {
            return target == COMPLETED || target == FAILED;
        }
    },
    COMPLETED("환불 완료") {
        @Override
        public boolean canTransitTo(RefundStatus target) {
            return false;
        }
    },
    // 실패한 환불은 이력으로 남기고, 다시 요청하면 새 환불을 만든다 (E-3)
    FAILED("환불 실패") {
        @Override
        public boolean canTransitTo(RefundStatus target) {
            return false;
        }
    };

    private final String description;

    public abstract boolean canTransitTo(RefundStatus target);
}