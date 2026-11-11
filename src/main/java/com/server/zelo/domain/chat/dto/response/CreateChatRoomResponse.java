package com.server.zelo.domain.chat.dto.response;

import com.server.zelo.domain.chat.entity.ChatRoom;
import java.time.LocalDateTime;

public record CreateChatRoomResponse(
        Long roomId,
        String title,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt
) {

    public static CreateChatRoomResponse from(ChatRoom chatRoom) {
        return new CreateChatRoomResponse(
                chatRoom.getId(),
                chatRoom.getTitle(),
                chatRoom.getLastMessageAt(),
                chatRoom.getCreatedAt());
    }
}
