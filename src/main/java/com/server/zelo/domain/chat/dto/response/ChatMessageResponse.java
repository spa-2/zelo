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
        LocalDateTime createdAt
) {

    public static ChatMessageResponse from(ChatMessage chatMessage) {
        return new ChatMessageResponse(
                chatMessage.getId(),
                chatMessage.getSender(),
                chatMessage.getContent(),
                chatMessage.getPayload(),
                chatMessage.getCreatedAt());
    }
}
