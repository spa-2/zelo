package com.server.zelo.domain.chat.service;

import com.server.zelo.ai.gateway.LlmGateway;
import com.server.zelo.ai.gateway.LlmReply;
import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.ai.prompt.ChatPrompt;
import com.server.zelo.ai.prompt.MenuPlanPrompt;
import com.server.zelo.ai.prompt.QuoteEditPrompt;
import com.server.zelo.ai.prompt.QuoteExplainPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.common.monitoring.LogContextInterceptor;
import com.server.zelo.domain.chat.dto.request.SearchChatMessageCondition;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.GetChatMessagesResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.dto.response.QuoteCardStatus;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.model.ChatIdempotencyRecord;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.service.QuoteService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

// @Transactional을 걸지 않는다: 전체를 한 트랜잭션으로 묶으면 LLM 응답을 기다리는 동안(재시도 포함 약 10초) DB 커넥션을 붙잡아
// 커넥션 풀(10개)이 채팅 요청에 묶이고 주문·결제까지 멈출 수 있다. 저장은 ChatMessageService의 짧은 트랜잭션으로 나눈다
@Service
@RequiredArgsConstructor
public class ChatMessageCommandService {

    // 견적은 발급됐는데 안내 문장만 받지 못했을 때 쓴다. 상품·가격은 카드에 있으므로 장보기는 이어 갈 수 있다
    private static final String QUOTE_READY = "견적을 준비했어요. 내용을 확인해 주세요.";
    // 지표의 결과 태그 값. 실패는 오류 코드(AI_xxx)를 그대로 쓴다
    private static final String SUCCESS = "SUCCESS";

    private final ChatPrompt chatPrompt;
    private final LlmGateway llmGateway;
    private final QuoteService quoteService;
    private final OrderService orderService;
    private final MenuPlanPrompt menuPlanPrompt;
    private final ProductService productService;
    private final BusinessMetrics businessMetrics;
    private final QuoteEditPrompt quoteEditPrompt;
    private final ChatMessageService chatMessageService;
    private final QuoteExplainPrompt quoteExplainPrompt;
    private final ChatRateLimitService chatRateLimitService;
    private final ChatIdempotencyService chatIdempotencyService;
    private final ChatQuoteCommandService chatQuoteCommandService;

    public ChatMessageResponse sendMessage(Long roomId, Long userId, String text) {
        return processMessage(roomId, userId, text, () -> { });
    }

    // onCommitted: 사용자 메시지를 커밋한 직후 부른다. 멱등 처리가 그 뒤의 실패를 "이미 저장된 요청"으로 구분하는 데 쓴다
    private ChatMessageResponse processMessage(Long roomId, Long userId, String text, Runnable onCommitted) {
        // 거절된 메시지가 답 없이 방에 남지 않게 저장 전에 센다
        chatRateLimitService.validateLimit(userId);
        // LLM이 실패해도 사용자가 보낸 말은 남아 있어야 하므로 먼저 저장(커밋)
        ChatMessage userMessage = chatMessageService.createUserMessage(roomId, userId, text);
        onCommitted.run();
        // LLM은 기억이 없어서 앞 대화를 같이 보낸다. 이번 메시지는 user로 따로 보내므로 기록에서 뺀다
        List<ChatMessage> history = chatMessageService.findHistory(roomId, userMessage.getId());
        return replyTo(roomId, userId, history, text, Function.identity());
    }

    // 재전송으로 같은 메시지가 두 번 처리되지 않게 한다. 키가 없으면(화면이 아직 보내지 않으면) 지금처럼 처리한다
    public ChatMessageResponse sendMessage(Long roomId, Long userId, String text, String idempotencyKey) {
        if (idempotencyKey == null) {
            return sendMessage(roomId, userId, text);
        }
        return chatIdempotencyService.runOnce(userId, idempotencyKey, "room:" + roomId + ":" + text,
                onCommitted -> processMessage(roomId, userId, text, onCommitted),
                response -> roomId, Function.identity(), firstResponse((savedRoomId, message) -> message));
    }

    public StartChatResponse startChat(Long userId, String text, String idempotencyKey) {
        if (idempotencyKey == null) {
            return startChat(userId, text);
        }
        return chatIdempotencyService.runOnce(userId, idempotencyKey, "start:" + text,
                onCommitted -> processStart(userId, text, onCommitted),
                StartChatResponse::roomId, StartChatResponse::message, firstResponse(StartChatResponse::of));
    }

    public StartChatResponse startChat(Long userId, String text) {
        return processStart(userId, text, () -> { });
    }

    private StartChatResponse processStart(Long userId, String text, Runnable onCommitted) {
        chatRateLimitService.validateLimit(userId);
        // 방과 첫 메시지를 먼저 커밋해 두어, LLM이 실패해도 사용자는 이 방에서 다시 보낼 수 있다
        ChatMessage userMessage = chatMessageService.createRoomWithFirstMessage(userId, text);
        onCommitted.run();
        Long roomId = userMessage.getRoomId();
        // 실패 응답에도 roomId를 담아야 프론트가 새로 생긴 방으로 이어 갈 수 있어, 성공·실패 모두 같은 모양으로 만든다.
        // 방금 만든 방이라 앞 대화가 없다
        // 첫 요청의 URL에는 roomId가 없어, 방 생성 후 답변을 만드는 동안 로그에 붙인다.
        try (MDC.MDCCloseable ignored = MDC.putCloseable(LogContextInterceptor.ROOM_ID, String.valueOf(roomId))) {
            return replyTo(roomId, userId, List.of(), text, message -> StartChatResponse.of(roomId, message));
        }
    }

    // FALLBACK의 [다시 시도]. 바로 앞 사용자 메시지로 답변만 다시 만들고, 사용자 메시지는 새로 저장하지 않는다.
    // 키는 화면이 아니라 서버가 FALLBACK 메시지 ID로 정해, 두 탭에서 눌러도 FALLBACK 하나당 한 번만 처리한다
    // (처리 중에 다시 오면 409, 끝난 뒤에 오면 첫 응답을 그대로 준다)
    public ChatMessageResponse retryMessage(Long roomId, Long userId, Long messageId) {
        String retryKey = "retry:" + messageId;
        String idempotencyKey = UUID.nameUUIDFromBytes(retryKey.getBytes(StandardCharsets.UTF_8)).toString();
        return chatIdempotencyService.runOnce(userId, idempotencyKey, "room:" + roomId + ":" + retryKey,
                onCommitted -> processRetry(roomId, userId, messageId),
                response -> roomId, Function.identity(), firstResponse((savedRoomId, message) -> message));
    }

    // 저장한 것 없이 답변만 다시 만들므로 onCommitted를 부르지 않는다. 그래서 응답 없이 실패하면 키가 지워져 다시 누를 수 있다
    // 다시 시도가 실제로 답을 살렸는지(SUCCESS), 또 실패했는지(AI_xxx), 거절됐는지(CHAT_004)를 센다
    private ChatMessageResponse processRetry(Long roomId, Long userId, Long messageId) {
        try {
            chatRateLimitService.validateLimit(userId);
            ChatMessage userMessage = chatMessageService.getRetryTarget(roomId, userId, messageId);
            List<ChatMessage> history = chatMessageService.findHistory(roomId, userMessage.getId());
            ChatMessageResponse response = replyTo(roomId, userId, history, userMessage.getContent(), Function.identity());
            businessMetrics.count(BusinessMetric.CHAT_RETRY, "result", SUCCESS);
            return response;
        } catch (BusinessException e) {
            businessMetrics.count(BusinessMetric.CHAT_RETRY, "result", e.getErrorCode().getCode());
            throw e;
        }
    }

    // 견적 카드는 저장된 그대로 두고, 목록을 줄 때마다 방의 현재 견적(Redis)과 주문을 보고 카드마다 상태를 붙인다.
    // 카드가 여러 장이어도 현재 견적 1번, 주문 1번만 조회하고, 카드가 없으면 둘 다 조회하지 않는다
    public GetChatMessagesResponse getMessages(Long roomId, Long userId, SearchChatMessageCondition condition) {
        GetChatMessagesResponse response = chatMessageService.getMessages(roomId, userId, condition);
        Map<Long, String> quoteIds = new HashMap<>();
        for (ChatMessageResponse message : response.messages()) {
            chatMessageService.findQuoteCardId(message.payload())
                    .ifPresent(quoteId -> quoteIds.put(message.messageId(), quoteId));
        }
        if (quoteIds.isEmpty()) {
            return response;
        }

        String currentQuoteId = quoteService.findQuote(roomId).map(Quote::quoteId).orElse(null);
        Set<String> orderedQuoteIds = orderService.findOrderedQuoteIds(quoteIds.values());
        List<ChatMessageResponse> messages = response.messages().stream()
                .map(message -> {
                    String quoteId = quoteIds.get(message.messageId());
                    return quoteId == null ? message
                            : message.withQuoteCardStatus(QuoteCardStatus.of(quoteId, currentQuoteId, orderedQuoteIds));
                })
                .toList();
        return GetChatMessagesResponse.of(messages, response.nextCursor(), response.hasNext());
    }

    // 멱등 키로 다시 온 요청에 줄 첫 응답을, 그때 저장한 AI 메시지로 다시 만든다
    private <T> Function<ChatIdempotencyRecord, T> firstResponse(BiFunction<Long, ChatMessageResponse, T> rebuild) {
        return record -> {
            ChatMessage message = chatMessageService.getMessage(record.messageId());
            // 첫 응답을 그대로 다시 주므로 retryable도 그때처럼 계산한다. 그 뒤 새 메시지가 왔으면 재시도 API가 거절한다
            ChatMessageResponse first = ChatMessageResponse.from(message, record.quoteCardStatus())
                    .withRetryable(chatMessageService.isRetryable(message.getPayload()));
            return rebuild.apply(record.roomId(), first);
        };
    }

    // 메시지가 장보기 요청인지, 견적 수정인지, 일반 질문인지를 메뉴 계획으로 먼저 판단하고 서버가 길을 나눈다.
    // 견적 발급·수정은 LLM이 아니라 서버가 직접 부른다(매번 같은 순서라 예측·테스트할 수 있고, LLM 재시도와 얽히지 않는다)
    // 메시지 하나를 답하는 데 걸린 전체 시간(LLM 여러 번 + 견적 발급·수정 + 저장)을 의도·결과별로 남긴다.
    // 의도를 정하기 전에 실패하면 intent=UNKNOWN이다
    private <T> T replyTo(Long roomId, Long userId, List<ChatMessage> history, String text,
                          Function<ChatMessageResponse, T> toResponse) {
        long start = System.nanoTime();
        String intent = "UNKNOWN";
        String outcome = SUCCESS;
        try {
            // 재료 목록은 요청마다 재료 테이블에서 읽어, 프롬프트와 답의 단위 검증이 같은 목록을 쓰게 한다
            Map<String, BaseUnit> ingredientUnits = productService.findIngredientUnits();
            // 담은 상품도 대체 후보도 없는 견적은 고칠 것도 주문할 것도 없어서 견적이 없는 것으로 본다
            Optional<Quote> currentQuote = quoteService.findQuote(roomId).filter(quote -> !ChatQuoteCommandService.isEmpty(quote));
            String currentMenuName = currentQuote.map(Quote::menuName).orElse(null);
            MenuPlan menuPlan = planMenu(roomId, userId, history, text, ingredientUnits, currentMenuName, toResponse);
            intent = menuPlan.intent().name();
            ChatMessage assistantMessage = switch (menuPlan.intent()) {
                case SHOPPING_REQUEST -> replyWithQuote(roomId, userId, menuPlan, toResponse);
                // 현재 견적이 "없음"이면 프롬프트가 QUOTE_EDIT를 막지만, LLM 답을 믿지 않고 한 번 더 본다
                case QUOTE_EDIT -> currentQuote
                        .map(quote -> editQuote(roomId, userId, history, text, ingredientUnits, quote, toResponse))
                        .orElseGet(() -> chatMessageService.createAssistantMessage(roomId, userId,
                                chatQuoteCommandService.noQuoteToEditReply()));
                case GENERAL_QUESTION -> chatMessageService.createAssistantMessage(roomId, userId,
                        explain(roomId, userId, chatPrompt.systemPrompt(), history, text, toResponse).text());
            };
            return toResponse.apply(assistantResponse(assistantMessage));
        } catch (BusinessException e) {
            outcome = e.getErrorCode().getCode();
            throw e;
        } catch (RuntimeException e) {
            outcome = ErrorCode.INTERNAL_ERROR.getCode();
            throw e;
        } finally {
            businessMetrics.record(BusinessMetric.CHAT_REPLY, Duration.ofNanos(System.nanoTime() - start),
                    "intent", intent, "outcome", outcome);
        }
    }

    // 방금 만든 카드는 방의 현재 견적이다
    private ChatMessageResponse assistantResponse(ChatMessage assistantMessage) {
        QuoteCardStatus quoteCardStatus = chatMessageService.findQuoteCardId(assistantMessage.getPayload())
                .map(quoteId -> QuoteCardStatus.ACTIVE)
                .orElse(null);
        return ChatMessageResponse.from(assistantMessage, quoteCardStatus);
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
        Optional<Quote> created = chatQuoteCommandService.createQuote(userId, roomId, menuPlan);
        if (created.isEmpty()) {
            return chatMessageService.createAssistantMessage(roomId, userId, ChatQuoteCommandService.QUOTE_IN_PROGRESS);
        }
        Quote quote = created.get();
        return replyWithQuoteCard(roomId, userId, quote, quoteExplainPrompt.userText(menuPlan, quote), toResponse);
    }

    // 무엇을 고칠지는 LLM이 수정 계획으로 정하고, 실제 수정은 LLM 호출이 끝난 뒤 서버가 한다.
    // 그래서 LLM 재시도·시간 초과 때문에 견적이 두 번 고쳐지거나, 고쳐진 채로 실패 응답이 나가지 않는다
    private <T> ChatMessage editQuote(Long roomId, Long userId, List<ChatMessage> history, String text,
                                      Map<String, BaseUnit> ingredientUnits, Quote quote,
                                      Function<ChatMessageResponse, T> toResponse) {
        QuoteEditPlan editPlan = planQuoteEdit(roomId, userId, history, text, ingredientUnits, quote, toResponse);
        ChatQuoteCommandService.ChatEditResult result = chatQuoteCommandService.applyEditPlan(userId, roomId, quote, editPlan);
        if (result.reply() != null) {
            return chatMessageService.createAssistantMessage(roomId, userId, result.reply());
        }
        ChatQuoteCommandService.EditResult edit = result.edit();
        String editedText = quoteExplainPrompt.editedText(quote, edit.edited(), edit.appliedEdits(),
                edit.failedEdit(), edit.failure());
        return replyWithQuoteCard(roomId, userId, edit.edited(), editedText, toResponse);
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


    private <T> ChatMessage replyWithQuoteCard(Long roomId, Long userId, Quote quote, String quoteText,
                                               Function<ChatMessageResponse, T> toResponse) {
        if (ChatQuoteCommandService.isEmpty(quote)) {
            // 담은 상품도 고를 후보도 없으면 카드 없이 빠진 이유만 안내한다. 후보만 있으면 고를 수 있게 카드를 저장한다
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
            businessMetrics.count(BusinessMetric.CHAT_EXPLAIN_DEGRADED, "code", e.getErrorCode().getCode());
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
        businessMetrics.count(BusinessMetric.CHAT_FALLBACK, "code", e.getErrorCode().getCode());
        // 방금 저장한 FALLBACK이 방의 마지막 메시지라 오류 코드만 보고 [다시 시도] 여부를 정한다
        T data = toResponse.apply(ChatMessageResponse.from(fallbackMessage)
                .withRetryable(chatMessageService.isRetryable(fallbackMessage.getPayload())));
        return new BusinessException(e.getErrorCode(), e.getMessage(), data);
    }

}
