package com.server.zelo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.request.SearchChatMessageCondition;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.GetChatMessagesResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.repository.ChatMessageRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    void 요청한_개수보다_메시지가_많으면_개수만큼_돌려주고_마지막_ID를_다음_커서로_준다() {
        // given: size 2를 요청하면 다음 페이지 확인용으로 3개를 조회한다
        given(chatMessageRepository.findByRoomIdOrderByIdDesc(10L, Limit.of(3)))
                .willReturn(messagesWithIds(30L, 20L, 10L));

        // when
        GetChatMessagesResponse response =
                chatMessageService.getMessages(10L, 1L, new SearchChatMessageCondition(null, 2));

        // then
        assertThat(response.messages()).extracting(ChatMessageResponse::messageId).containsExactly(30L, 20L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(20L);
    }

    @Test
    void 요청한_개수_이하면_다음_페이지가_없고_커서는_null이다() {
        // given
        given(chatMessageRepository.findByRoomIdOrderByIdDesc(10L, Limit.of(3)))
                .willReturn(messagesWithIds(30L, 20L));

        // when
        GetChatMessagesResponse response =
                chatMessageService.getMessages(10L, 1L, new SearchChatMessageCondition(null, 2));

        // then
        assertThat(response.messages()).extracting(ChatMessageResponse::messageId).containsExactly(30L, 20L);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void 커서를_주면_그보다_오래된_메시지를_조회한다() {
        // given
        given(chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(10L, 20L, Limit.of(3)))
                .willReturn(messagesWithIds(10L));

        // when
        GetChatMessagesResponse response =
                chatMessageService.getMessages(10L, 1L, new SearchChatMessageCondition(20L, 2));

        // then
        assertThat(response.messages()).extracting(ChatMessageResponse::messageId).containsExactly(10L);
        then(chatMessageRepository).should(never()).findByRoomIdOrderByIdDesc(any(), any());
    }

    @Test
    void 개수를_정하지_않으면_30개를_기준으로_조회한다() {
        // given
        given(chatMessageRepository.findByRoomIdOrderByIdDesc(10L, Limit.of(31))).willReturn(List.of());

        // when
        GetChatMessagesResponse response =
                chatMessageService.getMessages(10L, 1L, new SearchChatMessageCondition(null, null));

        // then
        assertThat(response.messages()).isEmpty();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 다른_사용자의_방이면_메시지를_조회하지_않는다() {
        // given
        given(chatRoomService.getOwnedChatRoom(10L, 2L))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> chatMessageService.getMessages(10L, 2L, new SearchChatMessageCondition(null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
        then(chatMessageRepository).should(never()).findByRoomIdOrderByIdDesc(any(), any());
    }

    private List<ChatMessage> messagesWithIds(Long... ids) {
        List<ChatMessage> messages = new ArrayList<>();
        for (Long id : ids) {
            ChatMessage chatMessage = ChatMessage.of(10L, ChatSender.USER, "메시지 " + id);
            ReflectionTestUtils.setField(chatMessage, "id", id);
            messages.add(chatMessage);
        }
        return messages;
    }
}
