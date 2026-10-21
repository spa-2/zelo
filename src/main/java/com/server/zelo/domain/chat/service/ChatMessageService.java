package com.server.zelo.domain.chat.service;

import com.server.zelo.common.exception.ErrorCode;
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

    // LLM 답을 받지 못했을 때 남기는 AI 메시지. 방을 다시 열어도 그때 받은 문구가 보이도록 저장하고,
    // 프론트와 이후 대화 처리는 payload.type(FALLBACK)으로 일반 답변과 구분한다
    private static final String FALLBACK_CONTENT = "지금은 답변을 드리기 어려워요. 잠시 후 다시 말씀해 주세요.";
    private static final String FALLBACK_PAYLOAD_FORMAT = "{\"type\":\"FALLBACK\",\"code\":\"%s\"}";

    private final ChatRoomService chatRoomService;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional
    public ChatMessage createUserMessage(Long roomId, Long userId, String content) {
        ChatRoom chatRoom = chatRoomService.getOwnedChatRoom(roomId, userId);
        return saveUserMessage(chatRoom, roomId, content);
    }

    // 방 생성과 첫 메시지 저장을 한 트랜잭션으로 묶어, 메시지 저장이 실패하면 방도 남지 않게 한다(빈 방 방지)
    @Transactional
    public ChatMessage createRoomWithFirstMessage(Long userId, String content) {
        ChatRoom chatRoom = chatRoomService.createAndGetChatRoom(userId);
        return saveUserMessage(chatRoom, chatRoom.getId(), content);
    }

    @Transactional
    public ChatMessage createAssistantMessage(Long roomId, Long userId, String content) {
        return saveAssistantMessage(roomId, userId, content, null);
    }

    @Transactional
    public ChatMessage createFallbackMessage(Long roomId, Long userId, ErrorCode errorCode) {
        String payload = FALLBACK_PAYLOAD_FORMAT.formatted(errorCode.getCode());
        return saveAssistantMessage(roomId, userId, FALLBACK_CONTENT, payload);
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

    // LLM 응답을 기다리는 사이 방 상태가 바뀌었을 수 있어 저장 직전에 소유자를 다시 확인
    private ChatMessage saveAssistantMessage(Long roomId, Long userId, String content, String payload) {
        ChatRoom chatRoom = chatRoomService.getOwnedChatRoom(roomId, userId);
        ChatMessage chatMessage = chatMessageRepository.save(ChatMessage.of(roomId, ChatSender.ASSISTANT, content, payload));
        chatRoom.updateLastMessageAt(LocalDateTime.now());
        return chatMessage;
    }

    private ChatMessage saveUserMessage(ChatRoom chatRoom, Long roomId, String content) {
        ChatMessage chatMessage = chatMessageRepository.save(ChatMessage.of(roomId, ChatSender.USER, content));
        chatRoom.updateTitleIfAbsent(content);
        chatRoom.updateLastMessageAt(LocalDateTime.now());
        return chatMessage;
    }
}
