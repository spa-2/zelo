package com.server.zelo.domain.webhook.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.webhook.dto.request.WebhookRequest;
import com.server.zelo.domain.webhook.entity.WebhookEvent;
import com.server.zelo.domain.webhook.entity.WebhookStatus;
import com.server.zelo.domain.webhook.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class WebhookEventServiceTest {

    private static final String WEBHOOK_ID = "wh-1";
    private static final String BODY = "{\"type\":\"Transaction.Paid\"}";
    private static final WebhookRequest REQUEST =
            new WebhookRequest("Transaction.Paid", new WebhookRequest.Data("payment-uuid-1"));

    @InjectMocks
    private WebhookEventService webhookEventService;

    @Mock
    private WebhookEventRepository webhookEventRepository;

    @Test
    void 처음_받은_웹훅은_수신_상태로_저장한다() {
        // given
        given(webhookEventRepository.findByWebhookId(WEBHOOK_ID)).willReturn(Optional.empty());
        given(webhookEventRepository.save(any(WebhookEvent.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        WebhookEvent event = webhookEventService.receive(WEBHOOK_ID, REQUEST, BODY);

        // then
        assertThat(event.getWebhookId()).isEqualTo(WEBHOOK_ID);
        assertThat(event.getPortoneId()).isEqualTo("payment-uuid-1");
        assertThat(event.getEventType()).isEqualTo("Transaction.Paid");
        assertThat(event.getStatus()).isEqualTo(WebhookStatus.RECEIVED);
    }

    @Test
    void 이미_받은_웹훅이면_새로_저장하지_않고_기존_이벤트를_돌려준다() {
        // given
        WebhookEvent existing = WebhookEvent.of(WEBHOOK_ID, "payment-uuid-1", "Transaction.Paid", BODY);
        given(webhookEventRepository.findByWebhookId(WEBHOOK_ID)).willReturn(Optional.of(existing));

        // when
        WebhookEvent event = webhookEventService.receive(WEBHOOK_ID, REQUEST, BODY);

        // then
        assertThat(event).isSameAs(existing);
        then(webhookEventRepository).should(never()).save(any());
    }

    @Test
    void 처리하면_완료_상태가_된다() {
        // given
        WebhookEvent event = WebhookEvent.of(WEBHOOK_ID, "payment-uuid-1", "Transaction.Paid", BODY);
        given(webhookEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        webhookEventService.process(1L, LocalDateTime.now());

        // then
        assertThat(event.getStatus()).isEqualTo(WebhookStatus.PROCESSED);
    }

    @Test
    void 실패하면_실패_상태와_사유가_남는다() {
        // given
        WebhookEvent event = WebhookEvent.of(WEBHOOK_ID, "payment-uuid-1", "Transaction.Paid", BODY);
        given(webhookEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        webhookEventService.fail(1L, "포트원 조회 실패");

        // then
        assertThat(event.getStatus()).isEqualTo(WebhookStatus.FAILED);
        assertThat(event.getFailureReason()).isEqualTo("포트원 조회 실패");
    }

    @Test
    void 웹훅_이벤트가_없으면_예외가_발생한다() {
        // given
        given(webhookEventRepository.findById(1L)).willReturn(Optional.empty());

        // when // then
        assertThatThrownBy(() -> webhookEventService.fail(1L, "실패"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WEBHOOK_EVENT_NOT_FOUND);
    }

    @Test
    void 무시하면_무시_상태가_된다() {
        // given
        WebhookEvent event = WebhookEvent.of(WEBHOOK_ID, "payment-uuid-1", "Transaction.Ready", BODY);
        given(webhookEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        webhookEventService.ignore(1L);

        // then
        assertThat(event.getStatus()).isEqualTo(WebhookStatus.IGNORED);
    }
}