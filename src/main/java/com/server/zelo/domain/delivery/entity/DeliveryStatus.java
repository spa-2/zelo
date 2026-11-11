package com.server.zelo.domain.delivery.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DeliveryStatus {

    READY("배송 준비") {
        @Override
        public boolean canTransitTo(DeliveryStatus target) {
            return target == SHIPPING || target == CANCELED;
        }
    },
    SHIPPING("배송 중") {
        @Override
        public boolean canTransitTo(DeliveryStatus target) {
            return target == DELIVERED;
        }
    },
    DELIVERED("배송 완료") {
        @Override
        public boolean canTransitTo(DeliveryStatus target) {
            return false;
        }
    },
    CANCELED("배송 취소") {
        @Override
        public boolean canTransitTo(DeliveryStatus target) {
            return false;
        }
    };

    private final String description;

    public abstract boolean canTransitTo(DeliveryStatus target);
}