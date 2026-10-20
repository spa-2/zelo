package com.server.zelo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.server.zelo.ai.llm.LlmGateway;
import com.server.zelo.ai.llm.LlmReply;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatMessageOrchestratorTest {

    @Mock
    private LlmGateway llmGateway;

    @Mock
    private ChatMessageService chatMessageService;

    @InjectMocks
    private ChatMessageOrchestrator chatMessageOrchestrator;

    @Test
    void 사용자_메시지_저장_LLM_호출_AI_메시지_저장_순서로_처리하고_AI_메시지를_반환한다() {
        // given
        given(llmGateway.explain(anyString(), eq("김치볶음밥 재료")))
                .willReturn(LlmReply.of("김치와 밥이 필요해요.", "gpt-test", 10, 5));
        given(chatMessageService.createAssistantMessage(10L, 1L, "김치와 밥이 필요해요."))
                .willReturn(ChatMessage.of(10L, ChatSender.ASSISTANT, "김치와 밥이 필요해요."));

        // when
        ChatMessageResponse response = chatMessageOrchestrator.sendMessage(10L, 1L, "김치볶음밥 재료");

        // then
        InOrder order = inOrder(chatMessageService, llmGateway);
        order.verify(chatMessageService).createUserMessage(10L, 1L, "김치볶음밥 재료");
        order.verify(llmGateway).explain(anyString(), eq("김치볶음밥 재료"));
        order.verify(chatMessageService).createAssistantMessage(10L, 1L, "김치와 밥이 필요해요.");
        assertThat(response.sender()).isEqualTo(ChatSender.ASSISTANT);
        assertThat(response.content()).isEqualTo("김치와 밥이 필요해요.");
    }

    @Test
    void LLM을_쓸_수_없으면_고정_문구를_저장하고_그_실패_코드와_고정_문구_메시지를_담아_예외를_던진다() {
        // given
        given(llmGateway.explain(anyString(), anyString()))
                .willThrow(new BusinessException(ErrorCode.AI_TIMEOUT));
        given(chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_TIMEOUT))
                .willReturn(fallbackMessage(10L, "AI_002"));

        // when & then
        assertThatThrownBy(() -> chatMessageOrchestrator.sendMessage(10L, 1L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT);
                    assertThat(exception.getData()).isInstanceOfSatisfying(ChatMessageResponse.class, data -> {
                        assertThat(data.sender()).isEqualTo(ChatSender.ASSISTANT);
                        assertThat(data.payload()).isEqualTo("{\"type\":\"FALLBACK\",\"code\":\"AI_002\"}");
                    });
                });
        then(chatMessageService).should().createUserMessage(10L, 1L, "안녕");
        then(chatMessageService).should(never()).createAssistantMessage(anyLong(), anyLong(), anyString());
    }

    @Test
    void LLM_답을_저장하다_실패하면_고정_문구로_바꾸지_않고_그_오류를_그대로_던진다() {
        // given: LLM은 성공했지만 답을 저장하기 직전에 방 권한이 없어진 경우
        given(llmGateway.explain(anyString(), anyString())).willReturn(LlmReply.of("답변", "gpt-test", 10, 5));
        given(chatMessageService.createAssistantMessage(10L, 1L, "답변"))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> chatMessageOrchestrator.sendMessage(10L, 1L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
                    assertThat(exception.getData()).isNull();
                });
        then(chatMessageService).should(never()).createFallbackMessage(anyLong(), anyLong(), any());
    }

    @Test
    void 방_접근_권한이_없으면_LLM을_호출하지_않는다() {
        // given
        given(chatMessageService.createUserMessage(10L, 2L, "안녕"))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> chatMessageOrchestrator.sendMessage(10L, 2L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
        then(llmGateway).should(never()).explain(any(), any());
    }

    @Test
    void 새_대화는_방과_첫_메시지_저장_LLM_호출_AI_메시지_저장_순서로_처리하고_새_방_ID를_반환한다() {
        // given
        given(chatMessageService.createRoomWithFirstMessage(1L, "불고기 재료"))
                .willReturn(ChatMessage.of(77L, ChatSender.USER, "불고기 재료"));
        given(llmGateway.explain(anyString(), eq("불고기 재료")))
                .willReturn(LlmReply.of("소고기와 간장이 필요해요.", "gpt-test", 10, 5));
        given(chatMessageService.createAssistantMessage(77L, 1L, "소고기와 간장이 필요해요."))
                .willReturn(ChatMessage.of(77L, ChatSender.ASSISTANT, "소고기와 간장이 필요해요."));

        // when
        StartChatResponse response = chatMessageOrchestrator.startChat(1L, "불고기 재료");

        // then
        InOrder order = inOrder(chatMessageService, llmGateway);
        order.verify(chatMessageService).createRoomWithFirstMessage(1L, "불고기 재료");
        order.verify(llmGateway).explain(anyString(), eq("불고기 재료"));
        order.verify(chatMessageService).createAssistantMessage(77L, 1L, "소고기와 간장이 필요해요.");
        assertThat(response.roomId()).isEqualTo(77L);
        assertThat(response.message().sender()).isEqualTo(ChatSender.ASSISTANT);
        assertThat(response.message().content()).isEqualTo("소고기와 간장이 필요해요.");
    }

    @Test
    void 새_대화에서_LLM을_쓸_수_없으면_예외의_data에_새_방_ID와_고정_문구_메시지를_담는다() {
        // given
        given(chatMessageService.createRoomWithFirstMessage(1L, "안녕"))
                .willReturn(ChatMessage.of(77L, ChatSender.USER, "안녕"));
        given(llmGateway.explain(anyString(), anyString()))
                .willThrow(new BusinessException(ErrorCode.AI_CIRCUIT_OPEN));
        given(chatMessageService.createFallbackMessage(77L, 1L, ErrorCode.AI_CIRCUIT_OPEN))
                .willReturn(fallbackMessage(77L, "AI_008"));

        // when & then
        assertThatThrownBy(() -> chatMessageOrchestrator.startChat(1L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_CIRCUIT_OPEN);
                    assertThat(exception.getData()).isInstanceOfSatisfying(StartChatResponse.class, data -> {
                        assertThat(data.roomId()).isEqualTo(77L);
                        assertThat(data.message().payload()).contains("FALLBACK");
                    });
                });
        then(chatMessageService).should(never()).createAssistantMessage(anyLong(), anyLong(), anyString());
    }

    @Test
    void 새_대화의_첫_메시지_저장이_실패하면_LLM을_호출하지_않는다() {
        // given
        given(chatMessageService.createRoomWithFirstMessage(1L, " "))
                .willThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        // when & then
        assertThatThrownBy(() -> chatMessageOrchestrator.startChat(1L, " "))
                .isInstanceOf(BusinessException.class);
        then(llmGateway).should(never()).explain(any(), any());
    }

    private ChatMessage fallbackMessage(Long roomId, String code) {
        String payload = "{\"type\":\"FALLBACK\",\"code\":\"" + code + "\"}";
        return ChatMessage.of(roomId, ChatSender.ASSISTANT, "지금은 답변을 드리기 어려워요.", payload);
    }
}
