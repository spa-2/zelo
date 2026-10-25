package com.server.zelo.domain.webhook.service;

import com.server.zelo.domain.payment.service.PaymentFacade;
import com.server.zelo.domain.webhook.dto.request.WebhookRequest;
import com.server.zelo.domain.webhook.entity.WebhookEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookCommandService {

    private final PaymentFacade paymentFacade;
    private final WebhookEventService webhookEventService;
    private final TransactionOperations transactionOperations;

    public void handle(String webhookId, WebhookRequest request, String payload) {
        WebhookEvent webhookEvent = Objects.requireNonNull(
                transactionOperations.execute(status -> webhookEventService.receive(webhookId, request, payload)));
        if (webhookEvent.isProcessed()) {
            log.info("이미 처리한 웹훅 webhookId={}", webhookId);
            return;
        }

        try {
            paymentFacade.confirmPayment(request.data().paymentId());
        } catch (RuntimeException e) {
            transactionOperations.executeWithoutResult(
                    status -> webhookEventService.fail(webhookEvent.getId(), e.getMessage()));
            // 실패 응답을 받아야 포트원이 같은 웹훅을 다시 보낸다
            throw e;
        }

        transactionOperations.executeWithoutResult(
                status -> webhookEventService.process(webhookEvent.getId(), LocalDateTime.now()));
    }
}