package com.server.zelo.domain.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class ChatRoomTest {

    @Test
    void 채팅방을_생성하면_제목_없이_요청한_사용자의_방이_된다() {
        // given
        Long userId = 1L;

        // when
        ChatRoom chatRoom = ChatRoom.of(userId);

        // then
        assertThat(chatRoom.getUserId()).isEqualTo(userId);
        assertThat(chatRoom.getTitle()).isNull();
        assertThat(chatRoom.getLastMessageAt()).isNotNull();
    }

    @Test
    void 사용자_ID가_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> ChatRoom.of(null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 방_주인이면_소유자_검증을_통과한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);

        // when & then
        assertThat(chatRoom.isOwnedBy(1L)).isTrue();
        assertThatCode(() -> chatRoom.validateOwner(1L)).doesNotThrowAnyException();
    }

    @Test
    void 방_주인이_아니면_CHAT_ROOM_ACCESS_DENIED_예외가_발생한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);

        // when & then
        assertThat(chatRoom.isOwnedBy(2L)).isFalse();
        assertThatThrownBy(() -> chatRoom.validateOwner(2L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
    }
}
