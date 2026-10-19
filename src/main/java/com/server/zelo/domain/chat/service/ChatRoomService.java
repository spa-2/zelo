package com.server.zelo.domain.chat.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.CreateChatRoomResponse;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;

    @Transactional
    public CreateChatRoomResponse createChatRoom(Long userId) {
        ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.of(userId));
        return CreateChatRoomResponse.from(chatRoom);
    }

    @Transactional(readOnly = true)
    public ChatRoom getOwnedChatRoom(Long roomId, Long userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        chatRoom.validateOwner(userId);
        return chatRoom;
    }
}
