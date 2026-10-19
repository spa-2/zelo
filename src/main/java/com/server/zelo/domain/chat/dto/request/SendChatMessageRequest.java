package com.server.zelo.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendChatMessageRequest(
        // 메시지 길이가 곧 LLM 토큰 비용이라 상한을 지정
        @NotBlank
        @Size(max = 1000)
        String text
) {
}
