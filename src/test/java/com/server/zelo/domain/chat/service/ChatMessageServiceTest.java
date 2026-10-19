package com.server.zelo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.repository.ChatMessageRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    @Mock
    private ChatRoomService chatRoomService;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @InjectMocks
    private ChatMessageService chatMessageService;

    @Test
    void 사용자_메시지를_저장하고_방_제목과_마지막_대화_시각을_갱신한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        LocalDateTime before = chatRoom.getLastMessageAt();
        given(chatRoomService.getOwnedChatRoom(10L, 1L)).willReturn(chatRoom);
        given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(AdditionalAnswers.returnsFirstArg());

        // when
        ChatMessage result = chatMessageService.createUserMessage(10L, 1L, "김치볶음밥 재료");

        // then
        assertThat(result.getSender()).isEqualTo(ChatSender.USER);
        assertThat(result.getRoomId()).isEqualTo(10L);
        assertThat(result.getContent()).isEqualTo("김치볶음밥 재료");
        assertThat(chatRoom.getTitle()).isEqualTo("김치볶음밥 재료");
        assertThat(chatRoom.getLastMessageAt()).isAfterOrEqualTo(before);
    }

    @Test
    void 다른_사용자의_방이면_사용자_메시지를_저장하지_않는다() {
        // given
        given(chatRoomService.getOwnedChatRoom(10L, 2L))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> chatMessageService.createUserMessage(10L, 2L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    void AI_메시지를_저장하고_마지막_대화_시각만_갱신한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        chatRoom.updateTitleIfAbsent("첫 메시지");
        given(chatRoomService.getOwnedChatRoom(10L, 1L)).willReturn(chatRoom);
        given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(AdditionalAnswers.returnsFirstArg());

        // when
        ChatMessage result = chatMessageService.createAssistantMessage(10L, 1L, "재료는 김치와 밥이에요.");

        // then
        assertThat(result.getSender()).isEqualTo(ChatSender.ASSISTANT);
        assertThat(result.getContent()).isEqualTo("재료는 김치와 밥이에요.");
        assertThat(chatRoom.getTitle()).isEqualTo("첫 메시지");
    }

    @Test
    void AI_메시지_저장_직전에_방_접근_권한이_없으면_저장하지_않는다() {
        // given
        given(chatRoomService.getOwnedChatRoom(10L, 1L))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        // when & then
        assertThatThrownBy(() -> chatMessageService.createAssistantMessage(10L, 1L, "답변"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
        then(chatMessageRepository).should(never()).save(any());
    }
}
