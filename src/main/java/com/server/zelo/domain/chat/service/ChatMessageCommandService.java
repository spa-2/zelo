package com.server.zelo.domain.chat.service;

import com.server.zelo.ai.llm.ChatPrompt;
import com.server.zelo.ai.llm.LlmGateway;
import com.server.zelo.ai.llm.LlmReply;
import com.server.zelo.ai.llm.MenuPlan;
import com.server.zelo.ai.llm.MenuPlanPrompt;
import com.server.zelo.ai.llm.QuoteEdit;
import com.server.zelo.ai.llm.QuoteEditPlan;
import com.server.zelo.ai.llm.QuoteEditPrompt;
import com.server.zelo.ai.llm.QuoteExplainPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import com.server.zelo.domain.quote.service.QuoteService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

// @Transactional을 걸지 않는다: 전체를 한 트랜잭션으로 묶으면 LLM 응답을 기다리는 동안(재시도 포함 약 10초) DB 커넥션을 붙잡아
// 커넥션 풀(10개)이 채팅 요청에 묶이고 주문·결제까지 멈출 수 있다. 저장은 ChatMessageService의 짧은 트랜잭션으로 나눈다
@Service
@RequiredArgsConstructor
public class ChatMessageCommandService {

    // 견적은 발급됐는데 안내 문장만 받지 못했을 때 쓴다. 상품·가격은 카드에 있으므로 장보기는 이어 갈 수 있다
    private static final String QUOTE_READY = "견적을 준비했어요. 내용을 확인해 주세요.";
    // 아래 문장들은 견적을 고치지 못한 이유가 정해져 있어 LLM 없이 답한다. LLM이 고친 척 말하지 않게
    private static final String NO_QUOTE_TO_EDIT = "고칠 견적이 없어요. 견적이 만료됐을 수 있으니 만들 메뉴를 다시 말씀해 주세요.";
    private static final String ASK_EDIT_AGAIN = "어떤 상품을 어떻게 바꿀지 다시 말씀해 주세요.";
    private static final String NOT_IN_QUOTE = "견적 카드에 없는 상품이라 고치지 못했어요. 카드에 있는 상품 이름으로 다시 말씀해 주세요.";
    private static final String NOT_ENOUGH_STOCK = "재고가 부족해서 그 수량으로 바꿀 수 없어요. 수량을 줄여서 다시 말씀해 주세요.";
    // 견적 수정 메서드의 오류 중 사용자가 다시 말하면 되는 것. 그 밖의 오류(Redis 장애 등)는 그대로 던진다
    private static final Map<ErrorCode, String> EDIT_FAILURE_REPLIES = Map.of(
            ErrorCode.QUOTE_EXPIRED, NO_QUOTE_TO_EDIT,
            ErrorCode.PRODUCT_NOT_FOUND, NOT_IN_QUOTE,
            ErrorCode.INSUFFICIENT_STOCK, NOT_ENOUGH_STOCK);

    private final ChatPrompt chatPrompt;
    private final LlmGateway llmGateway;
    private final QuoteService quoteService;
    private final MenuPlanPrompt menuPlanPrompt;
    private final ProductService productService;
    private final QuoteEditPrompt quoteEditPrompt;
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
    // 견적 발급·수정은 LLM이 아니라 서버가 직접 부른다(매번 같은 순서라 예측·테스트할 수 있고, LLM 재시도와 얽히지 않는다)
    private <T> T replyTo(Long roomId, Long userId, List<ChatMessage> history, String text,
                          Function<ChatMessageResponse, T> toResponse) {
        // 재료 목록은 요청마다 재료 테이블에서 읽어, 프롬프트와 답의 단위 검증이 같은 목록을 쓰게 한다
        Map<String, BaseUnit> ingredientUnits = productService.findIngredientUnits();
        // 상품이 하나도 없는 견적은 고칠 것도 주문할 것도 없어서 견적이 없는 것으로 본다
        Optional<Quote> currentQuote = quoteService.findQuote(roomId).filter(quote -> !quote.items().isEmpty());
        String currentMenuName = currentQuote.map(Quote::menuName).orElse(null);
        MenuPlan menuPlan = planMenu(roomId, userId, history, text, ingredientUnits, currentMenuName, toResponse);
        ChatMessage assistantMessage = switch (menuPlan.intent()) {
            case SHOPPING_REQUEST -> replyWithQuote(roomId, userId, menuPlan, toResponse);
            // 현재 견적이 "없음"이면 프롬프트가 QUOTE_EDIT를 막지만, LLM 답을 믿지 않고 한 번 더 본다
            case QUOTE_EDIT -> currentQuote
                    .map(quote -> editQuote(roomId, userId, history, text, ingredientUnits, quote, toResponse))
                    .orElseGet(() -> chatMessageService.createAssistantMessage(roomId, userId, NO_QUOTE_TO_EDIT));
            case GENERAL_QUESTION -> chatMessageService.createAssistantMessage(roomId, userId,
                    explain(roomId, userId, chatPrompt.systemPrompt(), history, text, toResponse).text());
        };
        return toResponse.apply(ChatMessageResponse.from(assistantMessage));
    }

    // 현재 견적 메뉴는 "4인분으로"처럼 메뉴를 말하지 않은 요청과, 같은 메뉴에서 재료만 고치는 요청을 구분하는 데 쓴다
    private <T> MenuPlan planMenu(Long roomId, Long userId, List<ChatMessage> history, String text,
                                  Map<String, BaseUnit> ingredientUnits, String currentMenuName,
                                  Function<ChatMessageResponse, T> toResponse) {
        String systemPrompt = menuPlanPrompt.systemPrompt(ingredientUnits, currentMenuName);
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
        return replyWithQuoteCard(roomId, userId, quote, quoteExplainPrompt.userText(menuPlan, quote), toResponse);
    }

    // 무엇을 고칠지는 LLM이 수정 계획으로 정하고, 실제 수정은 LLM 호출이 끝난 뒤 서버가 한다.
    // 그래서 LLM 재시도·시간 초과 때문에 견적이 두 번 고쳐지거나, 고쳐진 채로 실패 응답이 나가지 않는다
    private <T> ChatMessage editQuote(Long roomId, Long userId, List<ChatMessage> history, String text,
                                      Map<String, BaseUnit> ingredientUnits, Quote quote,
                                      Function<ChatMessageResponse, T> toResponse) {
        QuoteEditPlan editPlan = planQuoteEdit(roomId, userId, history, text, ingredientUnits, quote, toResponse);
        if (editPlan.edits().isEmpty()) {
            String question = StringUtils.hasText(editPlan.question()) ? editPlan.question() : ASK_EDIT_AGAIN;
            return chatMessageService.createAssistantMessage(roomId, userId, question);
        }
        // 하나라도 현재 견적에 없는 상품을 가리키면 아무것도 고치지 않아, 견적이 일부만 바뀌지 않게 한다
        if (!editPlan.isApplicableTo(quote)) {
            return chatMessageService.createAssistantMessage(roomId, userId, NOT_IN_QUOTE);
        }

        // 수정마다 새 견적 ID로 저장되므로 마지막 견적으로 카드를 한 번만 만든다
        Quote edited = quote;
        List<QuoteEdit> appliedEdits = new ArrayList<>();
        for (QuoteEdit edit : editPlan.edits()) {
            try {
                edited = applyEdit(userId, roomId, edit);
                appliedEdits.add(edit);
            } catch (BusinessException e) {
                String reply = EDIT_FAILURE_REPLIES.get(e.getErrorCode());
                if (reply == null) {
                    throw e;
                }
                // 고친 것이 없으면 정해진 문장으로 답하고, 앞의 수정이 이미 저장됐으면 그 견적을 보여 주며 못 한 수정을 알린다
                if (appliedEdits.isEmpty()) {
                    return chatMessageService.createAssistantMessage(roomId, userId, reply);
                }
                String editedText = quoteExplainPrompt.editedText(quote, edited, appliedEdits, edit, e.getErrorCode());
                return replyWithQuoteCard(roomId, userId, edited, editedText, toResponse);
            }
        }
        String editedText = quoteExplainPrompt.editedText(quote, edited, appliedEdits, null, null);
        return replyWithQuoteCard(roomId, userId, edited, editedText, toResponse);
    }

    private <T> QuoteEditPlan planQuoteEdit(Long roomId, Long userId, List<ChatMessage> history, String text,
                                            Map<String, BaseUnit> ingredientUnits, Quote quote,
                                            Function<ChatMessageResponse, T> toResponse) {
        String systemPrompt = quoteEditPrompt.systemPrompt(quote, ingredientUnits);
        try {
            return llmGateway.planQuoteEdit(systemPrompt, history, text, ingredientUnits).content();
        } catch (BusinessException e) {
            throw fallback(roomId, userId, e, toResponse);
        }
    }

    private Quote applyEdit(Long userId, Long roomId, QuoteEdit edit) {
        return switch (edit.action()) {
            case REMOVE -> quoteCommandService.removeItem(userId, roomId, edit.productId());
            case CHANGE_QUANTITY -> quoteCommandService.changeQuantity(userId, roomId, edit.productId(), edit.quantity());
            case ADD -> quoteCommandService.addItem(userId, roomId, edit.ingredientName(), edit.amount(), edit.unit());
        };
    }

    private <T> ChatMessage replyWithQuoteCard(Long roomId, Long userId, Quote quote, String quoteText,
                                               Function<ChatMessageResponse, T> toResponse) {
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
