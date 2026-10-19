package com.server.zelo.domain.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import java.time.LocalDateTime;
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

    @Test
    void 제목이_없으면_첫_메시지로_제목을_정한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);

        // when
        chatRoom.updateTitleIfAbsent("김치볶음밥 재료 준비해 줘");

        // then
        assertThat(chatRoom.getTitle()).isEqualTo("김치볶음밥 재료 준비해 줘");
    }

    @Test
    void 제목이_이미_있으면_다음_메시지로_바뀌지_않는다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        chatRoom.updateTitleIfAbsent("첫 메시지");

        // when
        chatRoom.updateTitleIfAbsent("두 번째 메시지");

        // then
        assertThat(chatRoom.getTitle()).isEqualTo("첫 메시지");
    }

    @Test
    void 줄바꿈과_연속_공백은_한_칸으로_줄인다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);

        // when
        chatRoom.updateTitleIfAbsent("  토요일 저녁\n\n  넷이   먹을 거야  ");

        // then
        assertThat(chatRoom.getTitle()).isEqualTo("토요일 저녁 넷이 먹을 거야");
    }

    @Test
    void 서른_글자를_넘으면_서른_글자에서_자르고_말줄임표를_붙인다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        String thirtyOne = "가".repeat(31);

        // when
        chatRoom.updateTitleIfAbsent(thirtyOne);

        // then
        assertThat(chatRoom.getTitle()).isEqualTo("가".repeat(30) + "…");
    }

    @Test
    void 정확히_서른_글자면_자르지_않는다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);

        // when
        chatRoom.updateTitleIfAbsent("가".repeat(30));

        // then
        assertThat(chatRoom.getTitle()).isEqualTo("가".repeat(30));
    }

    @Test
    void 이모지는_반으로_잘리지_않는다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        String emojis = "🍚".repeat(31);

        // when
        chatRoom.updateTitleIfAbsent(emojis);

        // then
        assertThat(chatRoom.getTitle()).isEqualTo("🍚".repeat(30) + "…");
    }

    @Test
    void 빈_메시지로는_제목을_정하지_않는다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);

        // when
        chatRoom.updateTitleIfAbsent("   ");

        // then
        assertThat(chatRoom.getTitle()).isNull();
    }

    @Test
    void 마지막_대화_시각을_갱신한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        LocalDateTime messageAt = LocalDateTime.of(2026, 10, 19, 18, 0);

        // when
        chatRoom.updateLastMessageAt(messageAt);

        // then
        assertThat(chatRoom.getLastMessageAt()).isEqualTo(messageAt);
    }

    @Test
    void 마지막_대화_시각이_없으면_예외가_발생한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);

        // when & then
        assertThatThrownBy(() -> chatRoom.updateLastMessageAt(null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
