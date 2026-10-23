package com.server.zelo.domain.chat.service;

import com.server.zelo.ai.llm.ChatPrompt;
import com.server.zelo.ai.llm.LlmGateway;
import com.server.zelo.ai.llm.LlmReply;
import com.server.zelo.ai.llm.MenuPlan;
import com.server.zelo.ai.llm.MenuPlanPrompt;
import com.server.zelo.ai.llm.QuoteExplainPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// @Transactional을 걸지 않는다: 전체를 한 트랜잭션으로 묶으면 LLM 응답을 기다리는 동안(재시도 포함 약 10초) DB 커넥션을 붙잡아
// 커넥션 풀(10개)이 채팅 요청에 묶이고 주문·결제까지 멈출 수 있다. 저장은 ChatMessageService의 짧은 트랜잭션으로 나눈다
@Service
@RequiredArgsConstructor
public class ChatMessageCommandService {

    // 견적 수정 도구가 생기기 전까지는 LLM 없이 이 문장으로 답한다. LLM이 고친 척 말하지 않게
    private static final String QUOTE_EDIT_NOT_SUPPORTED = "지금은 견적을 부분만 고칠 수 없어요. 바꾸고 싶은 내용을 담아 다시 요청해 주세요.";
    // 견적은 발급됐는데 안내 문장만 받지 못했을 때 쓴다. 상품·가격은 카드에 있으므로 장보기는 이어 갈 수 있다
    private static final String QUOTE_READY = "견적을 준비했어요. 내용을 확인해 주세요.";

    private final ChatPrompt chatPrompt;
    private final LlmGateway llmGateway;
    private final MenuPlanPrompt menuPlanPrompt;
    private final ProductService productService;
    private final ChatMessageService chatMessageService;
    private final QuoteExplainPrompt quoteExplainPrompt;
    private final QuoteCommandService quoteCommandService;

    public ChatMessageResponse sendMessage(Long roomId, Long userId, String text) {
        // LLM이 실패해도 사용자가 보낸 말은 남아 있어야 하므로 먼저 저장(커밋)
        ChatMessage userMessage = chatMessageService.createUserMessage(roomId, userId, text);
        // LLM은 기억이 없어서 앞 대화를 같이 보낸다. 이번 메시지는 user로 따로 보내므로 기록에서 뺀다
        List<ChatMessage> history = chatMessageService.findHistory(roomId, userMessage.getId());
        return replyTo(roomId, userId, history, text, Function.identity());
    }

    public StartChatResponse startChat(Long userId, String text) {
        // 방과 첫 메시지를 먼저 커밋해 두어, LLM이 실패해도 사용자는 이 방에서 다시 보낼 수 있다
        ChatMessage userMessage = chatMessageService.createRoomWithFirstMessage(userId, text);
        Long roomId = userMessage.getRoomId();
        // 실패 응답에도 roomId를 담아야 프론트가 새로 생긴 방으로 이어 갈 수 있어, 성공·실패 모두 같은 모양으로 만든다.
        // 방금 만든 방이라 앞 대화가 없다
        return replyTo(roomId, userId, List.of(), text, message -> StartChatResponse.of(roomId, message));
    }

    // 메시지가 장보기 요청인지, 견적 수정인지, 일반 질문인지를 메뉴 계획으로 먼저 판단하고 서버가 길을 나눈다.
    // 견적 발급은 LLM이 아니라 서버가 직접 부른다(매번 같은 순서라 예측·테스트할 수 있고, LLM 재시도와 얽히지 않는다)
    private <T> T replyTo(Long roomId, Long userId, List<ChatMessage> history, String text,
                          Function<ChatMessageResponse, T> toResponse) {
        MenuPlan menuPlan = planMenu(roomId, userId, history, text, toResponse);
        ChatMessage assistantMessage = switch (menuPlan.intent()) {
            case SHOPPING_REQUEST -> replyWithQuote(roomId, userId, menuPlan, toResponse);
            case QUOTE_EDIT -> chatMessageService.createAssistantMessage(roomId, userId, QUOTE_EDIT_NOT_SUPPORTED);
            case GENERAL_QUESTION -> chatMessageService.createAssistantMessage(roomId, userId,
                    explain(roomId, userId, chatPrompt.systemPrompt(), history, text, toResponse).text());
        };
        return toResponse.apply(ChatMessageResponse.from(assistantMessage));
    }

    // 재료 목록은 요청마다 재료 테이블에서 읽어, 프롬프트와 답의 단위 검증이 같은 목록을 쓰게 한다
    private <T> MenuPlan planMenu(Long roomId, Long userId, List<ChatMessage> history, String text,
                                  Function<ChatMessageResponse, T> toResponse) {
        Map<String, BaseUnit> ingredientUnits = productService.findIngredientUnits();
        // TODO(#110 머지 후): 견적 담당 QuoteService로 현재 견적 메뉴명을 읽어 넘긴다. 그전까지는 견적이 없는 것으로 판단한다
        String systemPrompt = menuPlanPrompt.systemPrompt(ingredientUnits, null);
        try {
            return llmGateway.planMenu(systemPrompt, history, text, ingredientUnits).content();
        } catch (BusinessException e) {
            throw fallback(roomId, userId, e, toResponse);
        }
    }

    // 견적 발급이 실패하면(LLM과 상관없는 서버 오류) 고정 문구로 바꾸지 않고 그 오류를 그대로 던진다
    private <T> ChatMessage replyWithQuote(Long roomId, Long userId, MenuPlan menuPlan,
                                           Function<ChatMessageResponse, T> toResponse) {
        Quote quote = quoteCommandService.createQuote(userId, roomId, menuPlan);
        String quoteText = quoteExplainPrompt.userText(menuPlan, quote);
        if (quote.items().isEmpty()) {
            // 주문할 상품이 없는데 [예] 버튼이 뜨지 않도록 카드 없이 빠진 이유만 안내한다
            String reason = explain(roomId, userId, quoteExplainPrompt.systemPrompt(), List.of(), quoteText, toResponse).text();
            return chatMessageService.createAssistantMessage(roomId, userId, reason);
        }
        return chatMessageService.createQuoteCardMessage(roomId, userId, explainQuote(quoteText), QuoteCardPayload.from(quote));
    }

    // 견적은 이미 발급됐으므로 안내 문장만 실패해도 카드는 보여 준다
    private String explainQuote(String quoteText) {
        try {
            return llmGateway.explain(quoteExplainPrompt.systemPrompt(), List.of(), quoteText).text();
        } catch (BusinessException e) {
            return QUOTE_READY;
        }
    }

    private <T> LlmReply explain(Long roomId, Long userId, String systemPrompt, List<ChatMessage> history, String text,
                                 Function<ChatMessageResponse, T> toResponse) {
        try {
            return llmGateway.explain(systemPrompt, history, text);
        } catch (BusinessException e) {
            throw fallback(roomId, userId, e, toResponse);
        }
    }

    // LLM이 실패하면(장애, 빈 응답) 고정 문구를 AI 메시지로 저장한 뒤, 그 실패의 오류 코드(5xx)로 응답한다.
    // 응답의 data에는 성공 때와 같은 모양으로 고정 문구 메시지를 담는다(toResponse가 모양을 정한다).
    // try는 LLM 호출만 감싸, LLM 성공 뒤 저장 실패 같은 다른 오류를 고정 문구로 바꾸지 않는다.
    // 빈 응답 검사와 실패 로그는 ResilientLlmGateway가 하고, 모든 실패를 BusinessException(AI_xxx)으로 바꿔 던진다
    private <T> BusinessException fallback(Long roomId, Long userId, BusinessException e,
                                           Function<ChatMessageResponse, T> toResponse) {
        ChatMessage fallbackMessage = chatMessageService.createFallbackMessage(roomId, userId, e.getErrorCode());
        T data = toResponse.apply(ChatMessageResponse.from(fallbackMessage));
        return new BusinessException(e.getErrorCode(), e.getMessage(), data);
    }
}
