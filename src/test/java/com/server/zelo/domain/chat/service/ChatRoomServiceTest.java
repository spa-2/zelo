package com.server.zelo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.CreateChatRoomResponse;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @InjectMocks
    private ChatRoomService chatRoomService;

    @Test
    void 채팅방을_생성하면_요청한_사용자의_방으로_저장한다() {
        // given
        given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(AdditionalAnswers.returnsFirstArg());
        ArgumentCaptor<ChatRoom> chatRoomCaptor = ArgumentCaptor.forClass(ChatRoom.class);

        // when
        CreateChatRoomResponse response = chatRoomService.createChatRoom(1L);

        // then
        then(chatRoomRepository).should().save(chatRoomCaptor.capture());
        assertThat(chatRoomCaptor.getValue().getUserId()).isEqualTo(1L);
        assertThat(response.title()).isNull();
        assertThat(response.lastMessageAt()).isNotNull();
    }

    @Test
    void 같은_사용자가_여러_번_생성하면_매번_새_방을_저장한다() {
        // given
        given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(AdditionalAnswers.returnsFirstArg());

        // when
        chatRoomService.createChatRoom(1L);
        chatRoomService.createChatRoom(1L);

        // then
        then(chatRoomRepository).should(times(2)).save(any(ChatRoom.class));
    }

    @Test
    void 내_방을_조회하면_채팅방을_반환한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        given(chatRoomRepository.findById(10L)).willReturn(Optional.of(chatRoom));

        // when
        ChatRoom result = chatRoomService.getOwnedChatRoom(10L, 1L);

        // then
        assertThat(result).isSameAs(chatRoom);
    }

    @Test
    void 없는_방을_조회하면_CHAT_ROOM_NOT_FOUND_예외가_발생한다() {
        // given
        given(chatRoomRepository.findById(10L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> chatRoomService.getOwnedChatRoom(10L, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    @Test
    void 다른_사용자의_방을_조회하면_CHAT_ROOM_ACCESS_DENIED_예외가_발생한다() {
        // given
        given(chatRoomRepository.findById(10L)).willReturn(Optional.of(ChatRoom.of(1L)));

        // when & then
        assertThatThrownBy(() -> chatRoomService.getOwnedChatRoom(10L, 2L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
    }
}
