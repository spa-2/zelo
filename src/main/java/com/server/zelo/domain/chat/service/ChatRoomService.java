package com.server.zelo.domain.chat.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.response.CreateChatRoomResponse;
import com.server.zelo.domain.chat.dto.response.GetChatRoomResponse;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;

    @Transactional
    public CreateChatRoomResponse createChatRoom(Long userId) {
        return CreateChatRoomResponse.from(createAndGetChatRoom(userId));
    }

    // 새 대화 시작처럼 같은 트랜잭션 안에서 방을 만들고 바로 이어서 쓰는 곳을 위해 엔티티를 돌려준다
    @Transactional
    public ChatRoom createAndGetChatRoom(Long userId) {
        return chatRoomRepository.save(ChatRoom.of(userId));
    }

    @Transactional(readOnly = true)
    public List<GetChatRoomResponse> getChatRooms(Long userId) {
        return chatRoomRepository.findAllByUserIdOrderByLastMessageAtDescIdDesc(userId).stream()
                .map(GetChatRoomResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatRoom getOwnedChatRoom(Long roomId, Long userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        chatRoom.validateOwner(userId);
        return chatRoom;
    }
}
