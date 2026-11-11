package com.server.zelo.domain.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatSender;
import java.time.LocalDateTime;

// 메시지 전송 응답과 메시지 목록의 항목이 같은 모양이어야 해서 동작 이름 없이 두 API가 함께 사용
public record ChatMessageResponse(
        Long messageId,
        ChatSender sender,
        String content,
        // DB에 JSON 문자열로 저장된 값을 문자열이 아닌 JSON 객체로 처리
        @JsonRawValue
        String payload,
        QuoteCardStatus quoteCardStatus, // 견적 카드 메시지만 값이 있고 나머지는 null
        boolean retryable, // true면 화면이 [다시 시도] 버튼을 보여 준다. 방의 마지막 메시지인 FALLBACK 중 일시적 오류만 true
        LocalDateTime createdAt
) {

    public static ChatMessageResponse from(ChatMessage chatMessage) {
        return from(chatMessage, null);
    }

    // 방금 저장한 견적 카드는 방의 현재 견적이라 ACTIVE다
    public static ChatMessageResponse fromNewQuoteCard(ChatMessage chatMessage) {
        return from(chatMessage, QuoteCardStatus.ACTIVE);
    }

    public static ChatMessageResponse from(ChatMessage chatMessage, QuoteCardStatus quoteCardStatus) {
        return new ChatMessageResponse(
                chatMessage.getId(),
                chatMessage.getSender(),
                chatMessage.getContent(),
                chatMessage.getPayload(),
                quoteCardStatus,
                false,
                chatMessage.getCreatedAt());
    }

    public ChatMessageResponse withQuoteCardStatus(QuoteCardStatus quoteCardStatus) {
        return new ChatMessageResponse(messageId, sender, content, payload, quoteCardStatus, retryable, createdAt);
    }

    public ChatMessageResponse withRetryable(boolean retryable) {
        return new ChatMessageResponse(messageId, sender, content, payload, quoteCardStatus, retryable, createdAt);
    }
}
