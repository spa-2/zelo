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
    void LLM_호출이_실패하면_AI_RESPONSE_FAILED로_바꾸고_사용자_메시지만_남긴다() {
        // given: 외부 라이브러리가 던지는 예외를 흉내 낸다
        given(llmGateway.explain(anyString(), anyString())).willThrow(new RuntimeException("openai timeout"));

        // when & then
        assertThatThrownBy(() -> chatMessageOrchestrator.sendMessage(10L, 1L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_FAILED));
        then(chatMessageService).should().createUserMessage(10L, 1L, "안녕");
        then(chatMessageService).should(never()).createAssistantMessage(anyLong(), anyLong(), anyString());
    }

    @Test
    void LLM이_빈_응답을_주면_AI_RESPONSE_FAILED로_처리하고_AI_메시지는_저장하지_않는다() {
        // given
        given(llmGateway.explain(anyString(), anyString())).willReturn(LlmReply.of("  ", "gpt-test", 10, 0));

        // when & then
        assertThatThrownBy(() -> chatMessageOrchestrator.sendMessage(10L, 1L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_FAILED));
        then(chatMessageService).should(never()).createAssistantMessage(anyLong(), anyLong(), anyString());
    }

    @Test
    void LLM이_null을_돌려주면_AI_RESPONSE_FAILED로_처리한다() {
        // given
        given(llmGateway.explain(anyString(), anyString())).willReturn(null);

        // when & then
        assertThatThrownBy(() -> chatMessageOrchestrator.sendMessage(10L, 1L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_FAILED));
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
}
