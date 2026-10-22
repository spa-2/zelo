package com.server.zelo.domain.webhook.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.webhook.dto.request.WebhookRequest;
import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.WebhookVerifier;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@ConditionalOnProperty(name = "zelo.portone.mode", havingValue = "real")
public class PortOneWebhookVerifier {

    private final Validator validator;
    private final JsonMapper jsonMapper;
    private final WebhookVerifier webhookVerifier;

    public PortOneWebhookVerifier(@Value("${zelo.portone.webhook-secret}") String webhookSecret,
                                  JsonMapper jsonMapper, Validator validator) {
        // 시크릿이 없으면 모든 웹훅이 거절되므로 시작 단계에서 실패시킨다
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("포트원 웹훅 시크릿이 설정되지 않았습니다.");
        }
        this.webhookVerifier = new WebhookVerifier(webhookSecret);
        this.jsonMapper = jsonMapper;
        this.validator = validator;
    }

    // 서명을 검증한 뒤 본문을 객체로 바꾼다. SDK 예외는 공통 에러코드로 바꿔 401로 응답한다
    public WebhookRequest verify(String webhookId, String webhookTimestamp,
                                 String webhookSignature, String body) {
        
        if (webhookVerifier == null) { // ★
            log.error("포트원 웹훅 시크릿이 설정되지 않아 웹훅을 거절합니다 webhookId={}", webhookId);
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }
        try {
            webhookVerifier.verify(body, webhookId, webhookSignature, webhookTimestamp);
        } catch (WebhookVerificationException e) {
            log.warn("포트원 웹훅 서명 검증 실패 webhookId={}", webhookId);
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        } catch (IllegalArgumentException e) {
            // SDK는 서명을 확인한 뒤 본문을 포트원 웹훅 형식으로 바꾸고, 형식이 맞지 않으면 SerializationException을 던진다
            log.warn("포트원 웹훅 본문 형식 오류 webhookId={}", webhookId);
            throw new BusinessException(ErrorCode.INVALID_INPUT, "웹훅 본문 형식이 올바르지 않습니다.");
        }
        return read(body);
    }

    // 원문으로 받아 @Valid가 동작하지 않으므로 DTO 검증을 직접 실행한다
    private WebhookRequest read(String body) {
        try {
            WebhookRequest request = jsonMapper.readValue(body, WebhookRequest.class);
            if (request != null && validator.validate(request).isEmpty()) {
                return request;
            }
        } catch (JacksonException e) {
            log.warn("포트원 웹훅 본문 변환 실패", e);
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT, "웹훅 본문 형식이 올바르지 않습니다.");
    }
}