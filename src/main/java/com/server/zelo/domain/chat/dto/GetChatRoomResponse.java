package com.server.zelo.domain.chat.dto;

import com.server.zelo.domain.chat.entity.ChatRoom;
import java.time.LocalDateTime;

public record GetChatRoomResponse(
        Long roomId,
        String title,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt
) {

    public static GetChatRoomResponse from(ChatRoom chatRoom) {
        return new GetChatRoomResponse(
                chatRoom.getId(),
                chatRoom.getTitle(),
                chatRoom.getLastMessageAt(),
                chatRoom.getCreatedAt());
    }
}
