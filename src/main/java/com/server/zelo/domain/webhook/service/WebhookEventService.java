package com.server.zelo.domain.webhook.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.webhook.dto.request.WebhookRequest;
import com.server.zelo.domain.webhook.entity.WebhookEvent;
import com.server.zelo.domain.webhook.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class WebhookEventService {

    private final WebhookEventRepository webhookEventRepository;

    // 포트원이 재전송한 웹훅은 새로 저장하지 않고 기존 이벤트를 돌려줘 처리 여부를 보고 판단하게 한다
    public WebhookEvent receive(String webhookId, WebhookRequest request, String payload) {
        return webhookEventRepository.findByWebhookId(webhookId)
                .orElseGet(() -> webhookEventRepository.save(
                        WebhookEvent.of(webhookId, request.data().paymentId(), request.type(), payload)));
    }

    public void process(Long webhookEventId, LocalDateTime processedAt) {
        getWebhookEvent(webhookEventId).process(processedAt);
    }

    public void fail(Long webhookEventId, String failureReason) {
        getWebhookEvent(webhookEventId).fail(failureReason);
    }

    private WebhookEvent getWebhookEvent(Long webhookEventId) {
        return webhookEventRepository.findById(webhookEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WEBHOOK_EVENT_NOT_FOUND));
    }
}