package com.server.zelo.domain.webhook.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookEventTest {

    private static final LocalDateTime PROCESSED_AT = LocalDateTime.of(2026, 10, 25, 12, 0);

    @Test
    void 웹훅_이벤트를_만들면_수신_상태다() {
        // when
        WebhookEvent event = newEvent();

        // then
        assertThat(event.getStatus()).isEqualTo(WebhookStatus.RECEIVED);
        assertThat(event.isProcessed()).isFalse();
    }

    @Test
    void 처리하면_완료_상태와_처리_시각이_남는다() {
        // given
        WebhookEvent event = newEvent();

        // when
        event.process(PROCESSED_AT);

        // then
        assertThat(event.isProcessed()).isTrue();
        assertThat(event.getProcessedAt()).isEqualTo(PROCESSED_AT);
    }

    @Test
    void 실패한_웹훅을_다시_처리하면_완료되고_실패_사유가_지워진다() {
        // given
        WebhookEvent event = newEvent();
        event.fail("포트원 조회 실패");

        // when
        event.process(PROCESSED_AT);

        // then
        assertThat(event.getStatus()).isEqualTo(WebhookStatus.PROCESSED);
        assertThat(event.getFailureReason()).isNull();
    }

    @Test
    void 처리_완료된_웹훅은_실패로_바꿀_수_없다() {
        // given
        WebhookEvent event = newEvent();
        event.process(PROCESSED_AT);

        // when // then
        assertThatThrownBy(() -> event.fail("실패"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    private WebhookEvent newEvent() {
        return WebhookEvent.of("wh-1", "payment-uuid-1", "Transaction.Paid", "{}");
    }
}