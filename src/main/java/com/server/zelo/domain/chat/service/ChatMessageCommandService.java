package com.server.zelo.domain.chat.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.common.monitoring.LogContextInterceptor;
import com.server.zelo.domain.chat.dto.request.SearchChatMessageCondition;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.GetChatMessagesResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardStatus;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.model.ChatIdempotencyRecord;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.service.QuoteService;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

// 메시지 시작·전송·재시도의 입구. 멱등 키 확인 → 횟수 제한 → 사용자 메시지 저장 → 답변(ChatReplyCommandService) 순서를 정한다.
// @Transactional을 걸지 않는다: LLM 응답을 기다리는 동안 DB 커넥션을 붙잡지 않게, 저장은 ChatMessageService의 짧은 트랜잭션으로 나눈다
@Service
@RequiredArgsConstructor
public class ChatMessageCommandService {

    // 다시 시도 지표의 결과 태그 값. 실패는 오류 코드(AI_xxx, CHAT_004 등)를 그대로 쓴다
    private static final String SUCCESS = "SUCCESS";

    private final QuoteService quoteService;
    private final OrderService orderService;
    private final BusinessMetrics businessMetrics;
    private final ChatMessageService chatMessageService;
    private final ChatRateLimitService chatRateLimitService;
    private final ChatIdempotencyService chatIdempotencyService;
    private final ChatReplyCommandService chatReplyCommandService;

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
        return chatReplyCommandService.replyTo(roomId, userId, history, text, Function.identity());
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
            return chatReplyCommandService.replyTo(roomId, userId, List.of(), text,
                    message -> StartChatResponse.of(roomId, message));
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
            ChatMessageResponse response = chatReplyCommandService.replyTo(roomId, userId, history,
                    userMessage.getContent(), Function.identity());
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
}
