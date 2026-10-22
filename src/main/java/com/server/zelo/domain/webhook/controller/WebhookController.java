package com.server.zelo.domain.webhook.controller;

import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.domain.webhook.dto.request.ReceivePaymentWebhookRequest;
import com.server.zelo.domain.payment.facade.PaymentFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Profile({"local", "dev"})
@RequestMapping("/api/payments")
public class WebhookController {

    private final PaymentFacade paymentFacade;

    // TODO(webhook): 포트원 웹훅 서명 검증 추가. 웹훅 내용은 믿지 않고 포트원에 다시 조회하므로 가짜 요청으로 결제를 완료시킬 수는 없다
    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<Void>> receiveWebhook(@Valid @RequestBody ReceivePaymentWebhookRequest request) {
        paymentFacade.confirmPayment(request.data().paymentId());
        return ResponseEntity.ok(ApiResponse.ok());
    }
}