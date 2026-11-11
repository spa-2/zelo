package com.server.zelo.domain.chat.dto.response;

// 새로 만든 방의 ID로 이후 메시지 전송·목록 조회를 이어 가고, message는 메시지 전송 응답과 같은 모양이다
public record StartChatResponse(
        Long roomId,
        ChatMessageResponse message
) {

    public static StartChatResponse of(Long roomId, ChatMessageResponse message) {
        return new StartChatResponse(roomId, message);
    }
}
