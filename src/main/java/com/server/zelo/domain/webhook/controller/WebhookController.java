package com.server.zelo.domain.webhook.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.domain.payment.service.PaymentFacade;
import com.server.zelo.domain.webhook.dto.request.WebhookRequest;
import com.server.zelo.domain.webhook.service.PortOneWebhookVerifier;
import com.server.zelo.domain.webhook.service.WebhookCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 웹훅은 실제 포트원과 연결된 real 모드에서만 받는다
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.portone.mode", havingValue = "real")
public class WebhookController {

    private final WebhookCommandService webhookCommandService;
    private final PortOneWebhookVerifier portOneWebhookVerifier;

    // 서명은 포트원이 보낸 본문 그대로 계산해야 해서 원문으로 받는다
    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<Void>> receiveWebhook(
            @RequestHeader(name = "webhook-id", required = false) String webhookId,
            @RequestHeader(name = "webhook-timestamp", required = false) String webhookTimestamp,
            @RequestHeader(name = "webhook-signature", required = false) String webhookSignature,
            @RequestBody String body) {
        WebhookRequest request =
                portOneWebhookVerifier.verify(webhookId, webhookTimestamp, webhookSignature, body);
        webhookCommandService.handle(webhookId, request, body);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}