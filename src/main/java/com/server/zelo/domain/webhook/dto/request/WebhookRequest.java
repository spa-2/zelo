package com.server.zelo.domain.webhook.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 포트원 V2 웹훅 본문 중 필요한 값만 받는다. 실제 형식은 포트원 웹훅 문서와 대조한다
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookRequest(
        @NotBlank
        String type,

        @Valid
        @NotNull
        Data data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @NotBlank
            String paymentId
    ) {
    }
}