package com.server.zelo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.response.CreateChatRoomResponse;
import com.server.zelo.domain.chat.dto.response.GetChatRoomResponse;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.repository.ChatRoomRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    void 채팅방_목록을_조회하면_저장소가_돌려준_순서대로_응답으로_바꾼다() {
        // given
        ChatRoom recent = ChatRoom.of(1L);
        ChatRoom old = ChatRoom.of(1L);
        ReflectionTestUtils.setField(recent, "id", 20L);
        ReflectionTestUtils.setField(old, "id", 10L);
        given(chatRoomRepository.findAllByUserIdOrderByLastMessageAtDescIdDesc(1L)).willReturn(List.of(recent, old));

        // when
        List<GetChatRoomResponse> result = chatRoomService.getChatRooms(1L);

        // then
        assertThat(result).extracting(GetChatRoomResponse::roomId).containsExactly(20L, 10L);
        assertThat(result).extracting(GetChatRoomResponse::title).containsOnlyNulls();
    }

    @Test
    void 채팅방이_없으면_빈_목록을_반환한다() {
        // given
        given(chatRoomRepository.findAllByUserIdOrderByLastMessageAtDescIdDesc(1L)).willReturn(List.of());

        // when
        List<GetChatRoomResponse> result = chatRoomService.getChatRooms(1L);

        // then
        assertThat(result).isEmpty();
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

    @Test
    void 방을_만들어_저장된_엔티티를_그대로_돌려준다() {
        // given
        given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(AdditionalAnswers.returnsFirstArg());

        // when
        ChatRoom result = chatRoomService.createAndGetChatRoom(1L);

        // then
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getTitle()).isNull();
        then(chatRoomRepository).should().save(result);
    }
}
