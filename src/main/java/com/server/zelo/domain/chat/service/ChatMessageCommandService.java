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
import com.server.zelo.domain.chat.dto.request.SearchChatMessageCondition;
import com.server.zelo.domain.chat.dto.request.UpdateQuoteItemRequest;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.GetChatMessagesResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.dto.response.QuoteCardStatus;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import com.server.zelo.domain.quote.service.QuoteService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
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
    private static final String QUOTE_CHANGED = "견적이 방금 바뀌어서 고치지 않았어요. 최신 견적 카드를 확인하고 다시 말씀해 주세요.";
    // 카드 버튼 수정은 LLM 없이 무엇을 했는지만 정해진 문장으로 알린다
    private static final String ITEM_REMOVED = "%s 상품을 뺐어요.";
    private static final String ITEM_QUANTITY_CHANGED = "%s 상품을 %d개로 바꿨어요.";
    private static final String NO_ITEM_LEFT = " 견적에 남은 상품이 없어요. 만들 메뉴를 다시 말씀해 주세요.";
    private static final String QUOTE_IN_PROGRESS = "이 채팅방의 견적을 처리하고 있는 다른 요청이 있어요. 잠시 후 다시 말씀해 주세요.";
    // 견적 수정 메서드의 오류 중 사용자가 다시 말하면 되는 것. 그 밖의 오류(Redis 장애 등)는 그대로 던진다.
    // QUOTE_NOT_CURRENT는 수정 메서드가 아니라 계획 뒤 견적이 바뀌었을 때 applyEdits가 쓴다
    private static final Map<ErrorCode, String> EDIT_FAILURE_REPLIES = Map.of(
            ErrorCode.QUOTE_EXPIRED, NO_QUOTE_TO_EDIT,
            ErrorCode.QUOTE_NOT_CURRENT, QUOTE_CHANGED,
            ErrorCode.PRODUCT_NOT_FOUND, NOT_IN_QUOTE,
            ErrorCode.INSUFFICIENT_STOCK, NOT_ENOUGH_STOCK);

    private final ChatPrompt chatPrompt;
    private final LlmGateway llmGateway;
    private final QuoteService quoteService;
    private final OrderService orderService;
    private final MenuPlanPrompt menuPlanPrompt;
    private final ProductService productService;
    private final QuoteEditPrompt quoteEditPrompt;
    private final ChatRoomService chatRoomService;
    private final ChatMessageService chatMessageService;
    private final QuoteExplainPrompt quoteExplainPrompt;
    private final QuoteCommandService quoteCommandService;
    private final LockDistributeService lockDistributeService;

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

    // 견적 카드의 ✕, −/+ 버튼. LLM 없이 견적을 바로 고치고, 새 카드와 정해진 문장을 AI 메시지로 저장해 돌려준다.
    // 화면이 보던 카드(quoteId)가 지금 견적일 때만 고치며, 말로 고칠 때와 같은 방 락 안에서 다시 확인하고 적용한다
    public ChatMessageResponse updateQuoteItem(Long roomId, Long userId, UpdateQuoteItemRequest request) {
        chatRoomService.getOwnedChatRoom(roomId, userId);
        Optional<Quote> current = quoteService.findQuote(roomId);
        if (current.isEmpty()) {
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, NO_QUOTE_TO_EDIT));
        }
        Quote quote = current.get();
        if (!quote.quoteId().equals(request.quoteId())) {
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, QUOTE_CHANGED));
        }

        QuoteEdit edit = request.isRemove()
                ? QuoteEdit.of(QuoteEditAction.REMOVE, request.productId(), 0, "", 0, null)
                : QuoteEdit.of(QuoteEditAction.CHANGE_QUANTITY, request.productId(), request.quantity(), "", 0, null);
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(edit), "");
        Optional<EditResult> locked = withQuoteLock(roomId, () -> applyEdits(userId, roomId, quote, editPlan));
        if (locked.isEmpty()) {
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, QUOTE_IN_PROGRESS));
        }
        EditResult result = locked.get();
        if (result.appliedEdits().isEmpty()) {
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId,
                    EDIT_FAILURE_REPLIES.get(result.failure())));
        }

        String reply = buttonEditReply(quote, request);
        Quote edited = result.edited();
        // 마지막 상품을 빼면 주문할 것이 없어 카드 없이 알린다([주문하기] 버튼이 뜨지 않게)
        if (edited.items().isEmpty()) {
            return assistantResponse(chatMessageService.createAssistantMessage(roomId, userId, reply + NO_ITEM_LEFT));
        }
        return assistantResponse(chatMessageService.createQuoteCardMessage(roomId, userId, reply, QuoteCardPayload.from(edited)));
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
        return toResponse.apply(assistantResponse(assistantMessage));
    }

    // 방금 만든 카드는 방의 현재 견적이다
    private ChatMessageResponse assistantResponse(ChatMessage assistantMessage) {
        QuoteCardStatus quoteCardStatus = chatMessageService.findQuoteCardId(assistantMessage.getPayload())
                .map(quoteId -> QuoteCardStatus.ACTIVE)
                .orElse(null);
        return ChatMessageResponse.from(assistantMessage, quoteCardStatus);
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
            String question = StringUtils.hasText(editPlan.question()) ? editPlan.question() : ASK_EDIT_AGAIN;
            return chatMessageService.createAssistantMessage(roomId, userId, question);
        }
        // 하나라도 현재 견적에 없는 상품을 가리키면 아무것도 고치지 않아, 견적이 일부만 바뀌지 않게 한다
        if (!editPlan.isApplicableTo(quote)) {
            return chatMessageService.createAssistantMessage(roomId, userId, NOT_IN_QUOTE);
        }

        // 계획을 세우는 동안(LLM, 수 초) 같은 방의 견적이 바뀌었을 수 있어, 계획 때 본 견적이 아직 현재 견적일 때만 고친다.
        // 확인과 적용 사이에 같은 방의 다른 발급·수정이 끼지 않게 그 구간만 방 단위로 잠근다. LLM 호출까지 잠그면 같은 방의 다음 메시지가 락 대기(3초)를 넘겨 실패하므로, 안내 문장(LLM)과 메시지 저장은 락이 풀린 뒤에 한다
        Optional<EditResult> locked = withQuoteLock(roomId, () -> applyEdits(userId, roomId, quote, editPlan));
        if (locked.isEmpty()) {
            return chatMessageService.createAssistantMessage(roomId, userId, QUOTE_IN_PROGRESS);
        }
        EditResult result = locked.get();
        if (result.appliedEdits().isEmpty()) {
            return chatMessageService.createAssistantMessage(roomId, userId, EDIT_FAILURE_REPLIES.get(result.failure()));
        }
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
            return Optional.empty();
        }
    }

    // 락 안에서 실행한다. 현재 견적이 계획 때 본 견적(quoteId)과 다르면 아무것도 고치지 않는다
    private EditResult applyEdits(Long userId, Long roomId, Quote quote, QuoteEditPlan editPlan) {
        Optional<String> currentQuoteId = quoteService.findQuote(roomId).map(Quote::quoteId);
        if (currentQuoteId.isEmpty()) {
            return EditResult.notApplied(quote, ErrorCode.QUOTE_EXPIRED);
        }
        if (!currentQuoteId.get().equals(quote.quoteId())) {
            return EditResult.notApplied(quote, ErrorCode.QUOTE_NOT_CURRENT);
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
            // 주문할 상품이 없는데 [주문하기] 버튼이 뜨지 않도록 카드 없이 빠진 이유만 안내한다
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

    // 락 안에서 고친 결과. 고치지 못한 경우 failure에 그 이유를 담아, 안내 문장·카드는 락이 풀린 뒤에 만든다
    private record EditResult(Quote edited, List<QuoteEdit> appliedEdits, QuoteEdit failedEdit, ErrorCode failure) {

        static EditResult notApplied(Quote quote, ErrorCode failure) {
            return new EditResult(quote, List.of(), null, failure);
        }
    }
}
