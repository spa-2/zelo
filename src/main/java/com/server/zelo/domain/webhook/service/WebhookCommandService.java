package com.server.zelo.domain.webhook.service;

import com.server.zelo.domain.payment.service.PaymentFacade;
import com.server.zelo.domain.webhook.dto.request.WebhookRequest;
import com.server.zelo.domain.webhook.entity.WebhookEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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

    // 결제 확인은 결제 락이 전체를 감싸야 해서 Command가 PaymentFacade를 호출한다.
    // 포트원 조회가 외부 HTTP 호출이라 이벤트 저장과 결과 기록만 각각 트랜잭션으로 묶는다
    public void handle(String webhookId, WebhookRequest request, String payload) {
        WebhookEvent webhookEvent = receiveEvent(webhookId, request, payload);
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

    private WebhookEvent receiveEvent(String webhookId, WebhookRequest request, String payload) {
        try {
            return Objects.requireNonNull(
                    transactionOperations.execute(status -> webhookEventService.receive(webhookId, request, payload)));
        } catch (DataIntegrityViolationException e) {
            // 같은 webhook-id가 동시에 먼저 저장됐으므로 롤백된 뒤 새 트랜잭션에서 그 행을 다시 조회한다
            return Objects.requireNonNull(
                    transactionOperations.execute(status -> webhookEventService.receive(webhookId, request, payload)));
        }
    }
}