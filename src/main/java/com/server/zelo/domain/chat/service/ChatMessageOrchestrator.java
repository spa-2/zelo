package com.server.zelo.domain.chat.service;

import com.server.zelo.ai.llm.LlmGateway;
import com.server.zelo.ai.llm.LlmReply;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

// @Transactional을 걸지 않는다: 전체를 한 트랜잭션으로 묶으면 LLM 응답을 기다리는 동안(최대 5초) DB 커넥션을 붙잡아
// 커넥션 풀(10개)이 채팅 요청에 묶이고 주문·결제까지 멈출 수 있다. 저장은 ChatMessageService의 짧은 트랜잭션으로 나눈다
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageOrchestrator {

    // 견적 요청 작업에서 프롬프트 파일과 대화 기록 주입으로 바뀐다
    private static final String SYSTEM_PROMPT = "너는 장보기 도우미 Zelo야. 한국어로 짧고 친절하게 답해.";

    private final LlmGateway llmGateway;
    private final ChatMessageService chatMessageService;

    public ChatMessageResponse sendMessage(Long roomId, Long userId, String text) {
        // LLM이 실패해도 사용자가 보낸 말은 남아 있어야 하므로 먼저 저장(커밋)
        chatMessageService.createUserMessage(roomId, userId, text);

        LlmReply reply = askLlm(text);

        ChatMessage assistantMessage = chatMessageService.createAssistantMessage(roomId, userId, reply.text());
        return ChatMessageResponse.from(assistantMessage);
    }

    // LLM 쪽 실패(호출 오류, 빈 응답)는 사용자 입력 문제가 아니므로 공통 응답의 AI 오류로 바꿔 내보낸다
    private LlmReply askLlm(String text) {
        LlmReply reply;
        try {
            reply = llmGateway.explain(SYSTEM_PROMPT, text);
        } catch (RuntimeException e) {
            log.warn("LLM 호출 실패: {}", e.getClass().getSimpleName(), e);
            throw new BusinessException(ErrorCode.AI_RESPONSE_FAILED);
        }
        if (reply == null || !StringUtils.hasText(reply.text())) {
            log.warn("LLM 빈 응답");
            throw new BusinessException(ErrorCode.AI_RESPONSE_FAILED);
        }
        return reply;
    }
}
