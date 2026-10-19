package com.server.zelo.domain.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ChatMessageTest {

    @Test
    void 메시지를_생성하면_방과_발화자와_내용을_갖고_payload는_비어_있다() {
        // when
        ChatMessage chatMessage = ChatMessage.of(10L, ChatSender.USER, "안녕");

        // then
        assertThat(chatMessage.getRoomId()).isEqualTo(10L);
        assertThat(chatMessage.getSender()).isEqualTo(ChatSender.USER);
        assertThat(chatMessage.getContent()).isEqualTo("안녕");
        assertThat(chatMessage.getPayload()).isNull();
    }

    @Test
    void 방_ID가_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> ChatMessage.of(null, ChatSender.USER, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 발화자가_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> ChatMessage.of(10L, null, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n"})
    void 내용이_비어_있으면_예외가_발생한다(String content) {
        // when & then
        assertThatThrownBy(() -> ChatMessage.of(10L, ChatSender.USER, content))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
