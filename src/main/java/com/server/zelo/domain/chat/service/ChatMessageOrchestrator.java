package com.server.zelo.domain.chat.service;

import com.server.zelo.ai.llm.LlmGateway;
import com.server.zelo.ai.llm.LlmReply;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// @Transactional을 걸지 않는다: 전체를 한 트랜잭션으로 묶으면 LLM 응답을 기다리는 동안(재시도 포함 약 10초) DB 커넥션을 붙잡아
// 커넥션 풀(10개)이 채팅 요청에 묶이고 주문·결제까지 멈출 수 있다. 저장은 ChatMessageService의 짧은 트랜잭션으로 나눈다
@Service
@RequiredArgsConstructor
public class ChatMessageOrchestrator {

    private final LlmGateway llmGateway;
    private final ChatMessageService chatMessageService;
    // 견적 요청 작업에서 프롬프트 파일과 대화 기록 주입으로 바뀐다
    private static final String SYSTEM_PROMPT = "너는 장보기 도우미 Zelo야. 한국어로 짧고 친절하게 답해.";

    public ChatMessageResponse sendMessage(Long roomId, Long userId, String text) {
        // LLM이 실패해도 사용자가 보낸 말은 남아 있어야 하므로 먼저 저장(커밋)
        chatMessageService.createUserMessage(roomId, userId, text);
        return replyTo(roomId, userId, text, Function.identity());
    }

    public StartChatResponse startChat(Long userId, String text) {
        // 방과 첫 메시지를 먼저 커밋해 두어, LLM이 실패해도 사용자는 이 방에서 다시 보낼 수 있다
        ChatMessage userMessage = chatMessageService.createRoomWithFirstMessage(userId, text);
        Long roomId = userMessage.getRoomId();
        // 실패 응답에도 roomId를 담아야 프론트가 새로 생긴 방으로 이어 갈 수 있어, 성공·실패 모두 같은 모양으로 만든다
        return replyTo(roomId, userId, text, message -> StartChatResponse.of(roomId, message));
    }

    // LLM이 실패하면(장애, 빈 응답) 고정 문구를 AI 메시지로 저장한 뒤, 그 실패의 오류 코드(5xx)로 응답한다.
    // 응답의 data에는 성공 때와 같은 모양으로 고정 문구 메시지를 담는다(toResponse가 모양을 정한다).
    // 빈 응답 검사와 실패 로그는 ResilientLlmGateway가 하고, 모든 실패를 BusinessException(AI_xxx)으로 바꿔 던진다
    private <T> T replyTo(Long roomId, Long userId, String text, Function<ChatMessageResponse, T> toResponse) {
        LlmReply reply;
        try {
            reply = llmGateway.explain(SYSTEM_PROMPT, text);
        } catch (BusinessException e) {
            ChatMessage fallbackMessage = chatMessageService.createFallbackMessage(roomId, userId, e.getErrorCode());
            T data = toResponse.apply(ChatMessageResponse.from(fallbackMessage));
            throw new BusinessException(e.getErrorCode(), e.getMessage(), data);
        }
        ChatMessage assistantMessage = chatMessageService.createAssistantMessage(roomId, userId, reply.text());
        return toResponse.apply(ChatMessageResponse.from(assistantMessage));
    }
}
