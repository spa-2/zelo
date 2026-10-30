package com.server.zelo.domain.chat.service;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.ai.plan.QuoteEditAction;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.ai.prompt.QuoteExplainPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.chat.dto.request.ChooseBudgetOptionRequest;
import com.server.zelo.domain.chat.dto.request.ChooseSubstituteRequest;
import com.server.zelo.domain.chat.dto.request.UpdateQuoteItemRequest;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.dto.response.QuoteCardStatus;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.quote.model.BudgetOption;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.SubstituteItem;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import com.server.zelo.domain.quote.service.QuoteService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

// 채팅에서 견적을 바꾸는 일(발급, 말로 고친 계획 적용, 카드 버튼)을 모두 같은 방 락과 quoteId 확인으로 한다.
// @Transactional을 걸지 않는다: 견적은 Redis에 있고, 메시지 저장은 ChatMessageService의 짧은 트랜잭션으로 나눈다
@Service
@RequiredArgsConstructor
public class ChatQuoteCommandService {

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
    // 견적 발급이 락을 얻지 못했을 때 ChatMessageCommandService도 쓴다
    static final String QUOTE_IN_PROGRESS = "이 채팅방의 견적을 처리하고 있는 다른 요청이 있어요. 잠시 후 다시 말씀해 주세요.";
    // 견적 수정 메서드의 오류 중 사용자가 다시 말하면 되는 것. 그 밖의 오류(Redis 장애 등)는 그대로 던진다.
    // QUOTE_NOT_CURRENT는 수정 메서드가 아니라 계획 뒤 견적이 바뀌었을 때 applyEdits가 쓴다
    private static final Map<ErrorCode, String> EDIT_FAILURE_REPLIES = Map.of(
            ErrorCode.QUOTE_EXPIRED, NO_QUOTE_TO_EDIT,
            ErrorCode.QUOTE_NOT_CURRENT, QUOTE_CHANGED,
            ErrorCode.PRODUCT_NOT_FOUND, NOT_IN_QUOTE,
            ErrorCode.INSUFFICIENT_STOCK, NOT_ENOUGH_STOCK);
    // 지표의 결과 태그 값. 실패는 ErrorCode 이름을 그대로 쓴다
    private static final String APPLIED = "APPLIED";
    private static final String PARTIAL = "PARTIAL";
    private static final String BUSY = "BUSY";

    private final QuoteService quoteService;
    private final BusinessMetrics businessMetrics;
    private final ChatRoomService chatRoomService;
    private final ChatMessageService chatMessageService;
    private final QuoteCommandService quoteCommandService;
    private final LockDistributeService lockDistributeService;

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

    // 발급도 방의 견적을 덮어쓰므로, 같은 방의 수정이 확인과 적용 사이에 있으면 끝날 때까지 기다린다. 락을 얻지 못하면 빈 값이다
    Optional<Quote> createQuote(Long userId, Long roomId, MenuPlan menuPlan) {
        return withQuoteLock(roomId, () -> quoteCommandService.createQuote(userId, roomId, menuPlan));
    }

    // 견적 수정으로 판단했는데 고칠 견적이 없을 때 LLM 없이 답할 문장
    String noQuoteToEditReply() {
        countEdit(EditSource.CHAT, ErrorCode.QUOTE_EXPIRED.name());
        return NO_QUOTE_TO_EDIT;
    }

    // LLM이 세운 수정 계획을 적용한다. 고치지 못했으면 정해진 문장(reply)을, 고쳤으면 고친 결과를 준다
    ChatEditResult applyEditPlan(Long userId, Long roomId, Quote quote, QuoteEditPlan editPlan) {
        if (editPlan.edits().isEmpty()) {
            countEdit(EditSource.CHAT, "ASK_AGAIN");
            return ChatEditResult.replied(StringUtils.hasText(editPlan.question()) ? editPlan.question() : ASK_EDIT_AGAIN);
        }
        // 하나라도 현재 견적에 없는 상품을 가리키면 아무것도 고치지 않아, 견적이 일부만 바뀌지 않게 한다
        if (!editPlan.isApplicableTo(quote)) {
            countEdit(EditSource.CHAT, "NOT_IN_QUOTE");
            return ChatEditResult.replied(NOT_IN_QUOTE);
        }

        // 계획을 세우는 동안(LLM, 수 초) 같은 방의 견적이 바뀌었을 수 있어, 계획 때 본 견적이 아직 현재 견적일 때만 고친다.
        // 확인과 적용 사이에 같은 방의 다른 발급·수정이 끼지 않게 그 구간만 방 단위로 잠근다. LLM 호출까지 잠그면 같은 방의 다음 메시지가 락 대기(3초)를 넘겨 실패하므로, 안내 문장(LLM)과 메시지 저장은 락이 풀린 뒤에 한다
        Optional<EditResult> locked = withQuoteLock(roomId, () -> applyEdits(userId, roomId, quote, editPlan));
        if (locked.isEmpty()) {
            countEdit(EditSource.CHAT, BUSY);
            return ChatEditResult.replied(QUOTE_IN_PROGRESS);
        }
        EditResult result = locked.get();
        if (result.appliedEdits().isEmpty()) {
            countEdit(EditSource.CHAT, result.failure().name());
            return ChatEditResult.replied(EDIT_FAILURE_REPLIES.get(result.failure()));
        }
        countEdit(EditSource.CHAT, result.failedEdit() == null ? APPLIED : PARTIAL);
        return ChatEditResult.edited(result);
    }

    // 방금 만든 카드는 방의 현재 견적이다
    private ChatMessageResponse assistantResponse(ChatMessage assistantMessage) {
        QuoteCardStatus quoteCardStatus = chatMessageService.findQuoteCardId(assistantMessage.getPayload())
                .map(quoteId -> QuoteCardStatus.ACTIVE)
                .orElse(null);
        return ChatMessageResponse.from(assistantMessage, quoteCardStatus);
    }

    // 담은 상품도 고를 후보도 없으면 카드를 보여 줄 것이 없다
    static boolean isEmpty(Quote quote) {
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
    record EditResult(Quote edited, List<QuoteEdit> appliedEdits, QuoteEdit failedEdit, ErrorCode failure) {

        static EditResult notApplied(Quote quote, ErrorCode failure) {
            return new EditResult(quote, List.of(), null, failure);
        }
    }

    // 말로 고친 결과. reply가 있으면 고치지 못해 그 문장으로 답하고, 없으면 edit의 견적으로 안내 문장과 카드를 만든다
    record ChatEditResult(String reply, EditResult edit) {

        static ChatEditResult replied(String reply) {
            return new ChatEditResult(reply, null);
        }

        static ChatEditResult edited(EditResult edit) {
            return new ChatEditResult(null, edit);
        }
    }
}
