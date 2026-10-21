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
    // 상품이 출고된 뒤라 이때부터는 주문을 취소할 수 없다 (E-1)
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