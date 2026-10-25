package com.server.zelo.domain.webhook.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum WebhookStatus {

    RECEIVED("수신") {
        @Override
        public boolean canTransitTo(WebhookStatus target) {
            return target == PROCESSED || target == FAILED;
        }
    },
    PROCESSED("처리 완료") {
        @Override
        public boolean canTransitTo(WebhookStatus target) {
            return false;
        }
    },
    // 포트원이 같은 웹훅을 다시 보내면 다시 처리하므로, 실패한 이벤트도 완료나 실패로 바뀔 수 있다
    FAILED("처리 실패") {
        @Override
        public boolean canTransitTo(WebhookStatus target) {
            return target == PROCESSED || target == FAILED;
        }
    };

    private final String description;

    public abstract boolean canTransitTo(WebhookStatus target);
}