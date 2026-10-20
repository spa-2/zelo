package com.server.zelo.domain.chat.service;

import com.server.zelo.domain.chat.dto.request.SearchChatMessageCondition;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.GetChatMessagesResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.repository.ChatMessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 메시지 한 건 저장을 하나의 짧은 트랜잭션으로 끝낸다. LLM 호출은 이 클래스 밖(트랜잭션 밖)에서 한다
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private final ChatRoomService chatRoomService;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional
    public ChatMessage createUserMessage(Long roomId, Long userId, String content) {
        ChatRoom chatRoom = chatRoomService.getOwnedChatRoom(roomId, userId);
        ChatMessage chatMessage = chatMessageRepository.save(ChatMessage.of(roomId, ChatSender.USER, content));
        chatRoom.updateTitleIfAbsent(content);
        chatRoom.updateLastMessageAt(LocalDateTime.now());
        return chatMessage;
    }

    // LLM 응답을 기다리는 사이 방 상태가 바뀌었을 수 있어 저장 직전에 소유자를 다시 확인
    @Transactional
    public ChatMessage createAssistantMessage(Long roomId, Long userId, String content) {
        ChatRoom chatRoom = chatRoomService.getOwnedChatRoom(roomId, userId);
        ChatMessage chatMessage = chatMessageRepository.save(ChatMessage.of(roomId, ChatSender.ASSISTANT, content));
        chatRoom.updateLastMessageAt(LocalDateTime.now());
        return chatMessage;
    }

    @Transactional(readOnly = true)
    public GetChatMessagesResponse getMessages(Long roomId, Long userId, SearchChatMessageCondition condition) {
        chatRoomService.getOwnedChatRoom(roomId, userId);

        int size = condition.sizeOrDefault();
        // count 쿼리 없이 다음 페이지 여부를 알기 위해 하나 더 가져온다
        Limit limit = Limit.of(size + 1);
        List<ChatMessage> fetched = condition.before() == null
                ? chatMessageRepository.findByRoomIdOrderByIdDesc(roomId, limit)
                : chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(roomId, condition.before(), limit);

        boolean hasNext = fetched.size() > size;
        List<ChatMessage> page = hasNext ? fetched.subList(0, size) : fetched;
        Long nextCursor = hasNext ? page.getLast().getId() : null;

        List<ChatMessageResponse> messages = page.stream()
                .map(ChatMessageResponse::from)
                .toList();
        return GetChatMessagesResponse.of(messages, nextCursor, hasNext);
    }
}
