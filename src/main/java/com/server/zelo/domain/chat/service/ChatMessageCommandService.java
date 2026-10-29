package com.server.zelo.domain.chat.service;

import com.server.zelo.ai.gateway.LlmGateway;
import com.server.zelo.ai.gateway.LlmReply;
import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.ai.plan.QuoteEditAction;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.ai.prompt.ChatPrompt;
import com.server.zelo.ai.prompt.MenuPlanPrompt;
import com.server.zelo.ai.prompt.QuoteEditPrompt;
import com.server.zelo.ai.prompt.QuoteExplainPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.common.util.HashUtil;
import com.server.zelo.domain.chat.dto.request.ChooseBudgetOptionRequest;
import com.server.zelo.domain.chat.dto.request.ChooseSubstituteRequest;
import com.server.zelo.domain.chat.dto.request.SearchChatMessageCondition;
import com.server.zelo.domain.chat.dto.request.UpdateQuoteItemRequest;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.GetChatMessagesResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.dto.response.QuoteCardStatus;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.model.ChatIdempotencyRecord;
import com.server.zelo.domain.chat.repository.ChatIdempotencyRepository;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.BudgetOption;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.SubstituteItem;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import com.server.zelo.domain.quote.service.QuoteService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

// @Transactional을 걸지 않는다: 전체를 한 트랜잭션으로 묶으면 LLM 응답을 기다리는 동안(재시도 포함 약 10초) DB 커넥션을 붙잡아
// 커넥션 풀(10개)이 채팅 요청에 묶이고 주문·결제까지 멈출 수 있다. 저장은 ChatMessageService의 짧은 트랜잭션으로 나눈다
@Slf4j
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
    private static final String QUOTE_CHANGED = "견적이 방금 바뀌어서 고치지 않았어요. 최신 견적 카드를 확인하고 다시 말씀해 주세요.";
    // 카드 버튼 수정은 LLM 없이 무엇을 했는지만 정해진 문장으로 알린다
    private static final String ITEM_REMOVED = "%s 상품을 뺐어요.";
    private static final String ITEM_QUANTITY_CHANGED = "%s 상품을 %d개로 바꿨어요.";
    private static final String NO_ITEM_LEFT = " 견적에 남은 상품이 없어요. 만들 메뉴를 다시 말씀해 주세요.";
    private static final String SUBSTITUTE_ACCEPTED = "%s 상품을 담았어요.";
    private static final String SUBSTITUTE_SOLD_OUT = "%s 상품도 방금 품절돼서 %s 재료는 뺐어요.";
    private static final String SUBSTITUTE_REJECTED = "%s 재료는 뺐어요.";
    // 대체 후보와 예산 후보에 같이 쓴다
    private static final String CANDIDATE_NOT_FOUND = "이미 고른 후보예요. 최신 견적 카드를 확인해 주세요.";
    private static final String BUDGET_OPTION_APPLIED = "%s 대신 %s 상품을 담았어요.";
    private static final String BUDGET_OPTION_SOLD_OUT = "%s 상품 재고가 부족해서 바꾸지 못했어요.";
    // 바꾼 뒤 예산 안인지는 서버가 계산해 붙인다
    private static final String WITHIN_BUDGET = " 합계 %,d원으로 예산 안에 들어왔어요.";
    private static final String STILL_OVER_BUDGET = " 아직 예산보다 %,d원 많아요.";
    private static final String QUOTE_IN_PROGRESS = "이 채팅방의 견적을 처리하고 있는 다른 요청이 있어요. 잠시 후 다시 말씀해 주세요.";
    // 견적 수정 메서드의 오류 중 사용자가 다시 말하면 되는 것. 그 밖의 오류(Redis 장애 등)는 그대로 던진다.
    // QUOTE_NOT_CURRENT는 수정 메서드가 아니라 계획 뒤 견적이 바뀌었을 때 applyEdits가 쓴다
    private static final Map<ErrorCode, String> EDIT_FAILURE_REPLIES = Map.of(
            ErrorCode.QUOTE_EXPIRED, NO_QUOTE_TO_EDIT,
            ErrorCode.QUOTE_NOT_CURRENT, QUOTE_CHANGED,
            ErrorCode.PRODUCT_NOT_FOUND, NOT_IN_QUOTE,
            ErrorCode.INSUFFICIENT_STOCK, NOT_ENOUGH_STOCK);
    // 지표의 결과 태그 값. 실패는 오류 코드(AI_xxx)나 ErrorCode 이름을 그대로 쓴다
    private static final String SUCCESS = "SUCCESS";
    private static final String APPLIED = "APPLIED";
    private static final String PARTIAL = "PARTIAL";
    private static final String BUSY = "BUSY";

    private final HashUtil hashUtil;
    private final ChatPrompt chatPrompt;
    private final LlmGateway llmGateway;
    private final QuoteService quoteService;
    private final OrderService orderService;
    private final MenuPlanPrompt menuPlanPrompt;
    private final ProductService productService;
    private final BusinessMetrics businessMetrics;
    private final QuoteEditPrompt quoteEditPrompt;
    private final ChatRoomService chatRoomService;
    private final ChatMessageService chatMessageService;
    private final QuoteExplainPrompt quoteExplainPrompt;
    private final QuoteCommandService quoteCommandService;
    private final LockDistributeService lockDistributeService;
    private final ChatIdempotencyRepository chatIdempotencyRepository;

    public ChatMessageResponse sendMessage(Long roomId, Long userId, String text) {
        return processMessage(roomId, userId, text, () -> { });
    }

    // onCommitted: 사용자 메시지를 커밋한 직후 부른다. 멱등 처리가 그 뒤의 실패를 "이미 저장된 요청"으로 구분하는 데 쓴다
    private ChatMessageResponse processMessage(Long roomId, Long userId, String text, Runnable onCommitted) {
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
        return runOnce(userId, idempotencyKey, "room:" + roomId + ":" + text,
                onCommitted -> processMessage(roomId, userId, text, onCommitted),
                response -> roomId, Function.identity(), (savedRoomId, message) -> message);
    }

    public StartChatResponse startChat(Long userId, String text, String idempotencyKey) {
        if (idempotencyKey == null) {
            return startChat(userId, text);
        }
        return runOnce(userId, idempotencyKey, "start:" + text,
                onCommitted -> processStart(userId, text, onCommitted),
                StartChatResponse::roomId, StartChatResponse::message, StartChatResponse::of);
    }

    public StartChatResponse startChat(Long userId, String text) {
        return processStart(userId, text, () -> { });
    }

    private StartChatResponse processStart(Long userId, String text, Runnable onCommitted) {
        // 방과 첫 메시지를 먼저 커밋해 두어, LLM이 실패해도 사용자는 이 방에서 다시 보낼 수 있다
        ChatMessage userMessage = chatMessageService.createRoomWithFirstMessage(userId, text);
        onCommitted.run();
        Long roomId = userMessage.getRoomId();
        // 실패 응답에도 roomId를 담아야 프론트가 새로 생긴 방으로 이어 갈 수 있어, 성공·실패 모두 같은 모양으로 만든다.
        // 방금 만든 방이라 앞 대화가 없다
        return replyTo(roomId, userId, List.of(), text, message -> StartChatResponse.of(roomId, message));
    }

    // FALLBACK의 [다시 시도]. 바로 앞 사용자 메시지로 답변만 다시 만들고, 사용자 메시지는 새로 저장하지 않는다.
    // 키는 화면이 아니라 서버가 FALLBACK 메시지 ID로 정해, 두 탭에서 눌러도 FALLBACK 하나당 한 번만 처리한다
    // (처리 중에 다시 오면 409, 끝난 뒤에 오면 첫 응답을 그대로 준다)
    public ChatMessageResponse retryMessage(Long roomId, Long userId, Long messageId) {
        String retryKey = "retry:" + messageId;
        String idempotencyKey = UUID.nameUUIDFromBytes(retryKey.getBytes(StandardCharsets.UTF_8)).toString();
        return runOnce(userId, idempotencyKey, "room:" + roomId + ":" + retryKey,
                onCommitted -> processRetry(roomId, userId, messageId),
                response -> roomId, Function.identity(), (savedRoomId, message) -> message);
    }

    // 저장한 것 없이 답변만 다시 만들므로 onCommitted를 부르지 않는다. 그래서 응답 없이 실패하면 키가 지워져 다시 누를 수 있다
    // 다시 시도가 실제로 답을 살렸는지(SUCCESS), 또 실패했는지(AI_xxx), 거절됐는지(CHAT_004)를 센다
    private ChatMessageResponse processRetry(Long roomId, Long userId, Long messageId) {
        try {
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

    // 같은 사용자의 같은 키는 한 번만 처리한다. 처리 중에 다시 오면 409, 끝난 뒤 다시 오면 저장해 둔 결과로 첫 응답을 다시 만든다.
    // 사용자 메시지를 커밋한 뒤의 실패(FALLBACK 포함)는 결과로 남겨, 같은 키로 다시 보내도 메시지·방이 또 저장되지 않게 한다.
    // 커밋 전 실패(방 권한 오류 등)는 저장된 것이 없으므로 키를 지워 같은 키로 다시 처리할 수 있게 한다
    private <T> T runOnce(Long userId, String idempotencyKey, String request, Function<Runnable, T> work,
                          Function<T, Long> roomIdOf, Function<T, ChatMessageResponse> messageOf,
                          BiFunction<Long, ChatMessageResponse, T> rebuild) {
        validateIdempotencyKey(idempotencyKey);
        String requestHash = hashUtil.hash(request);
        if (!chatIdempotencyRepository.saveIfAbsent(userId, idempotencyKey, ChatIdempotencyRecord.processing(requestHash))) {
            return replay(userId, idempotencyKey, requestHash, rebuild);
        }
        countIdempotency("FIRST");

        AtomicBoolean committed = new AtomicBoolean();
        T result;
        try {
            result = work.apply(() -> committed.set(true));
        } catch (BusinessException e) {
            if (e.getData() == null) {
                endWithoutResponse(userId, idempotencyKey, requestHash, committed.get(), e.getErrorCode());
                throw e;
            }
            // FALLBACK 예외의 data는 성공 응답과 같은 모양(T)으로 만든다(fallback의 toResponse)
            @SuppressWarnings("unchecked")
            T data = (T) e.getData();
            saveDone(userId, idempotencyKey,
                    ChatIdempotencyRecord.done(requestHash, roomIdOf.apply(data), messageOf.apply(data), e.getErrorCode()));
            throw e;
        } catch (RuntimeException e) {
            // 처리되지 않은 예외는 GlobalExceptionHandler가 500 INTERNAL_ERROR로 응답하므로 다시 올 때도 같은 코드로 준다
            endWithoutResponse(userId, idempotencyKey, requestHash, committed.get(), ErrorCode.INTERNAL_ERROR);
            throw e;
        }
        saveDone(userId, idempotencyKey,
                ChatIdempotencyRecord.done(requestHash, roomIdOf.apply(result), messageOf.apply(result), null));
        return result;
    }

    private <T> T replay(Long userId, String idempotencyKey, String requestHash,
                         BiFunction<Long, ChatMessageResponse, T> rebuild) {
        // 선점에 실패한 사이 TTL로 사라졌으면 처리 중이었던 것과 같게 보고 다시 보내게 한다
        Optional<ChatIdempotencyRecord> found = chatIdempotencyRepository.find(userId, idempotencyKey);
        if (found.isEmpty()) {
            countIdempotency("EXPIRED");
            throw new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
        }
        ChatIdempotencyRecord record = found.get();
        if (!record.requestHash().equals(requestHash)) {
            countIdempotency("KEY_REUSED");
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (!record.done()) {
            countIdempotency("IN_PROGRESS");
            throw new BusinessException(ErrorCode.TIMEOUT_EXCEPTION);
        }
        countIdempotency("REPLAY");
        if (record.messageId() == null) {
            throw new BusinessException(record.failure());
        }
        ChatMessage message = chatMessageService.getMessage(record.messageId());
        // 첫 응답을 그대로 다시 주므로 retryable도 그때처럼 계산한다. 그 뒤 새 메시지가 왔으면 재시도 API가 거절한다
        ChatMessageResponse first = ChatMessageResponse.from(message, record.quoteCardStatus())
                .withRetryable(chatMessageService.isRetryable(message.getPayload()));
        T response = rebuild.apply(record.roomId(), first);
        if (record.failure() != null) {
            throw new BusinessException(record.failure(), record.failure().getMessage(), response);
        }
        return response;
    }

    // 응답 메시지 없이 실패했을 때. 사용자 메시지를 이미 저장했으면 같은 오류로 끝난 것으로 남기고, 아니면 키를 지운다
    private void endWithoutResponse(Long userId, String idempotencyKey, String requestHash, boolean committed,
                                    ErrorCode failure) {
        if (committed) {
            saveDone(userId, idempotencyKey, ChatIdempotencyRecord.failed(requestHash, failure));
            return;
        }
        deleteQuietly(userId, idempotencyKey);
    }

    // 실패 처리 중이라 원래 예외를 그대로 던져야 한다. 삭제가 실패해도 처리 중 표시는 PROCESSING_TTL 뒤에 풀린다
    private void deleteQuietly(Long userId, String idempotencyKey) {
        try {
            chatIdempotencyRepository.delete(userId, idempotencyKey);
        } catch (RuntimeException e) {
            log.warn("메시지 전송 멱등 키 삭제 실패 userId={}", userId, e);
            businessMetrics.count(BusinessMetric.CHAT_IDEMPOTENCY_STORE_FAILURE, "operation", "DELETE");
        }
    }

    // 처리는 이미 끝났으니 결과 저장이 실패해도 응답은 그대로 보낸다. 처리 중 표시는 PROCESSING_TTL 뒤에 풀린다
    private void saveDone(Long userId, String idempotencyKey, ChatIdempotencyRecord record) {
        try {
            chatIdempotencyRepository.save(userId, idempotencyKey, record);
        } catch (RuntimeException e) {
            log.warn("메시지 전송 멱등 결과 저장 실패 userId={}", userId, e);
            businessMetrics.count(BusinessMetric.CHAT_IDEMPOTENCY_STORE_FAILURE, "operation", "SAVE");
        }
    }

    // 화면은 [보내기]마다 UUID를 만든다. 다른 모양은 잘못 만든 키라 거절한다
    private static void validateIdempotencyKey(String idempotencyKey) {
        try {
            UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private void countIdempotency(String result) {
        businessMetrics.count(BusinessMetric.CHAT_IDEMPOTENCY, "result", result);
    }

    // 견적 카드의 ✕, −/+ 버튼. LLM 없이 견적을 바로 고치고, 새 카드와 정해진 문장을 AI 메시지로 저장해 돌려준다.
    // 화면이 보던 카드(quoteId)가 지금 견적일 때만 고치며, 말로 고칠 때와 같은 방 락 안에서 다시 확인하고 적용한다
    public ChatMessageResponse updateQuoteItem(Long roomId, Long userId, UpdateQuoteItemRequest request) {
        chatRoomService.getOwnedChatRoom(roomId, userId);
        Optional<Quote> current = quoteService.findQuote(roomId);
        if (current.isEmpty()) {
            countEdit(EditSource.BUTTON, ErrorCode.QUOTE_EXPIRED.name());
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, NO_QUOTE_TO_EDIT));
        }
        Quote quote = current.get();
        if (!quote.quoteId().equals(request.quoteId())) {
            countEdit(EditSource.BUTTON, ErrorCode.QUOTE_NOT_CURRENT.name());
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, QUOTE_CHANGED));
        }

        QuoteEdit edit = request.isRemove()
                ? QuoteEdit.of(QuoteEditAction.REMOVE, request.productId(), 0, "", 0, null)
                : QuoteEdit.of(QuoteEditAction.CHANGE_QUANTITY, request.productId(), request.quantity(), "", 0, null);
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(edit), "");
        Optional<EditResult> locked = withQuoteLock(roomId, () -> applyEdits(userId, roomId, quote, editPlan));
        if (locked.isEmpty()) {
            countEdit(EditSource.BUTTON, BUSY);
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, QUOTE_IN_PROGRESS));
        }
        EditResult result = locked.get();
        if (result.appliedEdits().isEmpty()) {
            countEdit(EditSource.BUTTON, result.failure().name());
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId,
                    EDIT_FAILURE_REPLIES.get(result.failure())));
        }
        countEdit(EditSource.BUTTON, APPLIED);

        String reply = buttonEditReply(quote, request);
        Quote edited = result.edited();
        // 마지막 상품을 빼고 남은 후보도 없으면 고를 것도 주문할 것도 없어 카드 없이 알린다
        if (isEmpty(edited)) {
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, reply + NO_ITEM_LEFT));
        }
        return assistantResponse(chatMessageService.createQuoteCardMessage(roomId, userId, reply, QuoteCardPayload.from(edited)));
    }

    // 견적 카드 대체 후보의 [좋아요]·[빼기]. 카드 버튼 수정(updateQuoteItem)처럼 LLM 없이,
    // 화면이 보던 카드가 지금 견적일 때만 같은 방 락 안에서 고르고 새 카드와 정해진 문장을 돌려준다
    public ChatMessageResponse chooseSubstitute(Long roomId, Long userId, ChooseSubstituteRequest request) {
        chatRoomService.getOwnedChatRoom(roomId, userId);
        Optional<Quote> current = quoteService.findQuote(roomId);
        if (current.isEmpty()) {
            countEdit(EditSource.SUBSTITUTE, ErrorCode.QUOTE_EXPIRED.name());
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, NO_QUOTE_TO_EDIT));
        }
        Quote quote = current.get();
        if (!quote.quoteId().equals(request.quoteId())) {
            countEdit(EditSource.SUBSTITUTE, ErrorCode.QUOTE_NOT_CURRENT.name());
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, QUOTE_CHANGED));
        }
        Optional<SubstituteItem> substitute = quote.substitutes().stream()
                .filter(candidate -> candidate.name().equals(request.ingredientName()))
                .findFirst();
        if (substitute.isEmpty()) {
            countEdit(EditSource.SUBSTITUTE, ErrorCode.PRODUCT_NOT_FOUND.name());
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, CANDIDATE_NOT_FOUND));
        }

        Optional<EditResult> locked = withQuoteLock(roomId, () -> applyChoice(roomId, quote, () -> request.accept()
                ? quoteCommandService.acceptSubstitute(userId, roomId, request.ingredientName())
                : quoteCommandService.rejectSubstitute(userId, roomId, request.ingredientName())));
        if (locked.isEmpty()) {
            countEdit(EditSource.SUBSTITUTE, BUSY);
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, QUOTE_IN_PROGRESS));
        }
        EditResult result = locked.get();
        countEdit(EditSource.SUBSTITUTE, result.failure() == null ? APPLIED : result.failure().name());
        if (result.failure() != null) {
            // 견적 담당은 없는 후보를 PRODUCT_NOT_FOUND로 알린다(같은 후보를 두 번 누른 경우)
            String reply = result.failure() == ErrorCode.PRODUCT_NOT_FOUND
                    ? CANDIDATE_NOT_FOUND
                    : EDIT_FAILURE_REPLIES.get(result.failure());
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, reply));
        }

        Quote chosen = result.edited();
        String reply = substituteReply(quote, chosen, substitute.get(), request.accept());
        if (isEmpty(chosen)) {
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, reply + NO_ITEM_LEFT));
        }
        return assistantResponse(chatMessageService.createQuoteCardMessage(roomId, userId, reply, QuoteCardPayload.from(chosen)));
    }

    // 예산을 넘은 견적 카드의 더 싼 후보 [바꾸기]. 대체 후보 버튼(chooseSubstitute)과 같은 순서로,
    // 화면이 보던 카드가 지금 견적일 때만 같은 방 락 안에서 상품 하나만 바꾸고 새 카드와 정해진 문장을 돌려준다
    public ChatMessageResponse chooseBudgetOption(Long roomId, Long userId, ChooseBudgetOptionRequest request) {
        chatRoomService.getOwnedChatRoom(roomId, userId);
        Optional<Quote> current = quoteService.findQuote(roomId);
        if (current.isEmpty()) {
            countEdit(EditSource.BUDGET, ErrorCode.QUOTE_EXPIRED.name());
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, NO_QUOTE_TO_EDIT));
        }
        Quote quote = current.get();
        if (!quote.quoteId().equals(request.quoteId())) {
            countEdit(EditSource.BUDGET, ErrorCode.QUOTE_NOT_CURRENT.name());
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, QUOTE_CHANGED));
        }
        Optional<BudgetOption> option = quote.budgetOptions().stream()
                .filter(candidate -> candidate.productId().equals(request.productId()))
                .findFirst();
        if (option.isEmpty()) {
            countEdit(EditSource.BUDGET, ErrorCode.PRODUCT_NOT_FOUND.name());
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, CANDIDATE_NOT_FOUND));
        }

        Optional<EditResult> locked = withQuoteLock(roomId, () -> applyChoice(roomId, quote,
                () -> quoteCommandService.applyBudgetOption(userId, roomId, request.productId())));
        if (locked.isEmpty()) {
            countEdit(EditSource.BUDGET, BUSY);
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, QUOTE_IN_PROGRESS));
        }
        EditResult result = locked.get();
        countEdit(EditSource.BUDGET, result.failure() == null ? APPLIED : result.failure().name());
        if (result.failure() != null) {
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId,
                    budgetOptionFailureReply(option.get(), result.failure())));
        }
        // 상품을 바꾸기만 해서 담은 상품이 비지 않으므로 항상 카드를 저장한다
        Quote changed = result.edited();
        return assistantResponse(chatMessageService.createQuoteCardMessage(roomId, userId,
                budgetOptionReply(quote, changed, option.get()), QuoteCardPayload.from(changed)));
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
            Optional<Quote> currentQuote = quoteService.findQuote(roomId).filter(quote -> !isEmpty(quote));
            String currentMenuName = currentQuote.map(Quote::menuName).orElse(null);
            MenuPlan menuPlan = planMenu(roomId, userId, history, text, ingredientUnits, currentMenuName, toResponse);
            intent = menuPlan.intent().name();
            ChatMessage assistantMessage = switch (menuPlan.intent()) {
                case SHOPPING_REQUEST -> replyWithQuote(roomId, userId, menuPlan, toResponse);
                // 현재 견적이 "없음"이면 프롬프트가 QUOTE_EDIT를 막지만, LLM 답을 믿지 않고 한 번 더 본다
                case QUOTE_EDIT -> currentQuote
                        .map(quote -> editQuote(roomId, userId, history, text, ingredientUnits, quote, toResponse))
                        .orElseGet(() -> {
                            countEdit(EditSource.CHAT, ErrorCode.QUOTE_EXPIRED.name());
                            return chatMessageService.createAssistantMessage(roomId, userId, NO_QUOTE_TO_EDIT);
                        });
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

    // 담은 상품도 고를 후보도 없으면 카드를 보여 줄 것이 없다
    private static boolean isEmpty(Quote quote) {
        return quote.items().isEmpty() && quote.substitutes().isEmpty();
    }

    // 견적 담당은 [좋아요]를 누른 사이 품절되면 담지 않고 빠진 재료에 품절로 넣는다. 빠진 재료가 늘었으면 그 경우다
    private static String substituteReply(Quote before, Quote after, SubstituteItem substitute, boolean accept) {
        if (!accept) {
            return SUBSTITUTE_REJECTED.formatted(substitute.name());
        }
        String label = QuoteExplainPrompt.productLabel(substitute.item());
        return after.excludedItems().size() > before.excludedItems().size()
                ? SUBSTITUTE_SOLD_OUT.formatted(label, substitute.name())
                : SUBSTITUTE_ACCEPTED.formatted(label);
    }

    // 무엇을 바꿨는지와, 바꾼 뒤 예산 안에 들어왔는지(아니면 얼마 더 많은지)를 알린다.
    // 예산 후보는 예산이 있는 견적에만 있으므로 after.budget()은 null이 아니다
    private static String budgetOptionReply(Quote before, Quote after, BudgetOption option) {
        String replacedLabel = before.items().stream()
                .filter(item -> item.productId().equals(option.productId()))
                .findFirst()
                .map(QuoteExplainPrompt::productLabel)
                .orElseThrow();
        String reply = BUDGET_OPTION_APPLIED.formatted(replacedLabel, QuoteExplainPrompt.productLabel(option.item()));
        return reply + (after.overBudget()
                ? STILL_OVER_BUDGET.formatted(after.totalAmount() - after.budget())
                : WITHIN_BUDGET.formatted(after.totalAmount()));
    }

    // 견적 담당은 없는 후보(같은 후보를 두 번 누름)를 PRODUCT_NOT_FOUND로, 그사이 모자란 재고를 INSUFFICIENT_STOCK으로 알린다
    private static String budgetOptionFailureReply(BudgetOption option, ErrorCode failure) {
        return switch (failure) {
            case PRODUCT_NOT_FOUND -> CANDIDATE_NOT_FOUND;
            case INSUFFICIENT_STOCK -> BUDGET_OPTION_SOLD_OUT.formatted(QuoteExplainPrompt.productLabel(option.item()));
            default -> EDIT_FAILURE_REPLIES.get(failure);
        };
    }

    // 버튼으로 견적 상품을 수정한 뒤, 채팅에 보여줄 안내 문장을 만듦
    private String buttonEditReply(Quote quote, UpdateQuoteItemRequest request) {
        String label = quote.items().stream()
                .filter(item -> item.productId().equals(request.productId()))
                .findFirst()
                .map(QuoteExplainPrompt::productLabel)
                .orElseThrow();
        return request.isRemove()
                ? ITEM_REMOVED.formatted(label)
                : ITEM_QUANTITY_CHANGED.formatted(label, request.quantity());
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
        // 발급도 방의 견적을 덮어쓰므로, 같은 방의 수정이 확인과 적용 사이에 있으면 끝날 때까지 기다린다
        Optional<Quote> created = withQuoteLock(roomId, () -> quoteCommandService.createQuote(userId, roomId, menuPlan));
        if (created.isEmpty()) {
            return chatMessageService.createAssistantMessage(roomId, userId, QUOTE_IN_PROGRESS);
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
        if (editPlan.edits().isEmpty()) {
            countEdit(EditSource.CHAT, "ASK_AGAIN");
            String question = StringUtils.hasText(editPlan.question()) ? editPlan.question() : ASK_EDIT_AGAIN;
            return chatMessageService.createAssistantMessage(roomId, userId, question);
        }
        // 하나라도 현재 견적에 없는 상품을 가리키면 아무것도 고치지 않아, 견적이 일부만 바뀌지 않게 한다
        if (!editPlan.isApplicableTo(quote)) {
            countEdit(EditSource.CHAT, "NOT_IN_QUOTE");
            return chatMessageService.createAssistantMessage(roomId, userId, NOT_IN_QUOTE);
        }

        // 계획을 세우는 동안(LLM, 수 초) 같은 방의 견적이 바뀌었을 수 있어, 계획 때 본 견적이 아직 현재 견적일 때만 고친다.
        // 확인과 적용 사이에 같은 방의 다른 발급·수정이 끼지 않게 그 구간만 방 단위로 잠근다. LLM 호출까지 잠그면 같은 방의 다음 메시지가 락 대기(3초)를 넘겨 실패하므로, 안내 문장(LLM)과 메시지 저장은 락이 풀린 뒤에 한다
        Optional<EditResult> locked = withQuoteLock(roomId, () -> applyEdits(userId, roomId, quote, editPlan));
        if (locked.isEmpty()) {
            countEdit(EditSource.CHAT, BUSY);
            return chatMessageService.createAssistantMessage(roomId, userId, QUOTE_IN_PROGRESS);
        }
        EditResult result = locked.get();
        if (result.appliedEdits().isEmpty()) {
            countEdit(EditSource.CHAT, result.failure().name());
            return chatMessageService.createAssistantMessage(roomId, userId, EDIT_FAILURE_REPLIES.get(result.failure()));
        }
        countEdit(EditSource.CHAT, result.failedEdit() == null ? APPLIED : PARTIAL);
        String editedText = quoteExplainPrompt.editedText(quote, result.edited(), result.appliedEdits(),
                result.failedEdit(), result.failure());
        return replyWithQuoteCard(roomId, userId, result.edited(), editedText, toResponse);
    }

    // 방의 Redis 견적을 쓰는 발급·수정은 모두 이 락 안에서 한다. LLM 호출은 넣지 않는다(같은 방의 다음 요청이 3초 넘게 기다리지 않게).
    // 락 대기를 넘기면 빈 값이고, 호출한 쪽이 정해진 문장으로 답한다
    private <R> Optional<R> withQuoteLock(Long roomId, Supplier<R> work) {
        try {
            return Optional.of(lockDistributeService.execute(LockKey.quote(roomId), work));
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.TIMEOUT_EXCEPTION) {
                throw e;
            }
            businessMetrics.count(BusinessMetric.CHAT_QUOTE_BUSY);
            return Optional.empty();
        }
    }

    // 락 안에서 실행한다. 계획·버튼 때 본 견적(quoteId)이 아직 방의 현재 견적인지 보고, 아니면 그 이유를 준다
    private Optional<ErrorCode> staleReason(Long roomId, Quote quote) {
        Optional<String> currentQuoteId = quoteService.findQuote(roomId).map(Quote::quoteId);
        if (currentQuoteId.isEmpty()) {
            return Optional.of(ErrorCode.QUOTE_EXPIRED);
        }
        if (!currentQuoteId.get().equals(quote.quoteId())) {
            return Optional.of(ErrorCode.QUOTE_NOT_CURRENT);
        }
        return Optional.empty();
    }

    // 락 안에서 실행한다. 현재 견적이 계획 때 본 견적(quoteId)과 다르면 아무것도 고치지 않는다
    private EditResult applyEdits(Long userId, Long roomId, Quote quote, QuoteEditPlan editPlan) {
        Optional<ErrorCode> stale = staleReason(roomId, quote);
        if (stale.isPresent()) {
            return EditResult.notApplied(quote, stale.get());
        }

        // 수정마다 새 견적 ID로 저장되므로 마지막 견적으로 카드를 한 번만 만든다
        Quote edited = quote;
        List<QuoteEdit> appliedEdits = new ArrayList<>();
        for (QuoteEdit edit : editPlan.edits()) {
            try {
                edited = applyEdit(userId, roomId, edit);
                appliedEdits.add(edit);
            } catch (BusinessException e) {
                // 고친 것이 없으면 정해진 문장으로 답하거나(안내할 수 없는 오류는 그대로 던진다),
                // 앞의 수정이 이미 저장됐으면 오류 종류와 관계없이 그 견적을 보여 주며 못 한 수정을 알린다.
                // 그래야 채팅에 남는 카드가 저장된 견적(새 견적 ID)과 어긋나지 않는다
                if (appliedEdits.isEmpty() && !EDIT_FAILURE_REPLIES.containsKey(e.getErrorCode())) {
                    throw e;
                }
                return new EditResult(edited, appliedEdits, edit, e.getErrorCode());
            }
        }
        return new EditResult(edited, appliedEdits, null, null);
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

    // 락 안에서 실행한다. 후보 고르기(대체 후보, 예산 후보)는 수정 목록이 없어 성공하면 failure가 없는 결과를 준다
    private EditResult applyChoice(Long roomId, Quote quote, Supplier<Quote> choose) {
        Optional<ErrorCode> stale = staleReason(roomId, quote);
        if (stale.isPresent()) {
            return EditResult.notApplied(quote, stale.get());
        }
        try {
            return new EditResult(choose.get(), List.of(), null, null);
        } catch (BusinessException e) {
            if (!EDIT_FAILURE_REPLIES.containsKey(e.getErrorCode())) {
                throw e;
            }
            return EditResult.notApplied(quote, e.getErrorCode());
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
        if (isEmpty(quote)) {
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

    // 견적 수정이 어디서 왔고(source) 어떻게 끝났는지(result: APPLIED, PARTIAL, BUSY, 거절 이유) 센다
    private void countEdit(EditSource source, String result) {
        businessMetrics.count(BusinessMetric.CHAT_QUOTE_EDIT, "source", source.name(), "result", result);
    }

    private enum EditSource {
        CHAT, // 말로 고침 (LLM 수정 계획)
        BUTTON, // 카드 ✕, −/+ 버튼
        SUBSTITUTE, // 대체 후보 [좋아요]·[빼기]
        BUDGET // 예산 후보 [바꾸기]
    }

    // 락 안에서 고친 결과. 고치지 못한 경우 failure에 그 이유를 담아, 안내 문장·카드는 락이 풀린 뒤에 만든다
    private record EditResult(Quote edited, List<QuoteEdit> appliedEdits, QuoteEdit failedEdit, ErrorCode failure) {

        static EditResult notApplied(Quote quote, ErrorCode failure) {
            return new EditResult(quote, List.of(), null, failure);
        }
    }
}
