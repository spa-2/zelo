package com.server.zelo.domain.chat.service;

import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.ai.prompt.QuoteExplainPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.chat.dto.request.ChooseBudgetOptionRequest;
import com.server.zelo.domain.chat.dto.request.ChooseSubstituteRequest;
import com.server.zelo.domain.chat.dto.request.UpdateQuoteItemRequest;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.model.QuoteEditReply;
import com.server.zelo.domain.chat.model.QuoteEditResult;
import com.server.zelo.domain.chat.model.QuoteEditResult.Edited;
import com.server.zelo.domain.chat.model.QuoteEditResult.NotEdited;
import com.server.zelo.domain.chat.model.QuoteEditResult.PartiallyEdited;
import com.server.zelo.domain.chat.model.QuoteEditSource;
import com.server.zelo.domain.quote.model.BudgetOption;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.SubstituteItem;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import com.server.zelo.domain.quote.service.QuoteService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 채팅에서 견적을 고치는 일(카드 버튼, 말로 고친 계획 적용). 모두 ChatQuoteFacade의 방 단위 락 안에서 실행된다.
// @Transactional을 걸지 않는다: 견적은 Redis에 있고, 메시지 저장은 ChatMessageService의 짧은 트랜잭션으로 나눈다
@Service
@RequiredArgsConstructor
public class ChatQuoteCommandService {

    // 카드 버튼 수정은 LLM 없이 무엇을 했는지만 정해진 문장으로 알린다
    private static final String ITEM_REMOVED = "%s 상품을 뺐어요.";
    private static final String ITEM_QUANTITY_CHANGED = "%s 상품을 %d개로 바꿨어요.";
    private static final String NO_ITEM_LEFT = " 견적에 남은 상품이 없어요. 만들 메뉴를 다시 말씀해 주세요.";
    private static final String SUBSTITUTE_ACCEPTED = "%s 상품을 담았어요.";
    private static final String SUBSTITUTE_SOLD_OUT = "%s 상품도 방금 품절돼서 %s 재료는 뺐어요.";
    private static final String SUBSTITUTE_REJECTED = "%s 재료는 뺐어요.";
    private static final String BUDGET_OPTION_APPLIED = "%s 대신 %s 상품을 담았어요.";
    private static final String BUDGET_OPTION_SOLD_OUT = "%s 상품 재고가 부족해서 바꾸지 못했어요.";
    // 바꾼 뒤 예산 안인지는 서버가 계산해 붙인다
    private static final String WITHIN_BUDGET = " 합계 %,d원으로 예산 안에 들어왔어요.";
    private static final String STILL_OVER_BUDGET = " 아직 예산보다 %,d원 많아요.";
    // 지표의 결과 태그 값. 실패는 ErrorCode 이름을 그대로 쓴다
    private static final String APPLIED = "APPLIED";

    private final QuoteService quoteService;
    private final BusinessMetrics businessMetrics;
    private final ChatRoomService chatRoomService;
    private final ChatMessageService chatMessageService;
    private final QuoteCommandService quoteCommandService;

    // 견적 카드의 ✕, −/+ 버튼. LLM 없이 견적을 바로 고치고, 새 카드와 정해진 문장을 AI 메시지로 저장해 돌려준다.
    // 화면이 보던 카드(quoteId)가 지금 견적일 때만 고친다
    public ChatMessageResponse updateQuoteItem(Long roomId, Long userId, UpdateQuoteItemRequest request) {
        chatRoomService.getOwnedChatRoom(roomId, userId);
        Optional<Quote> current = quoteService.findQuote(roomId);
        Optional<ErrorCode> stale = staleReason(current.map(Quote::quoteId).orElse(null), request.quoteId());
        if (stale.isPresent()) {
            return reject(roomId, userId, QuoteEditSource.BUTTON, stale.get());
        }
        Quote quote = current.orElseThrow();

        Quote edited;
        try {
            edited = request.isRemove()
                    ? quoteCommandService.removeItem(userId, roomId, request.productId())
                    : quoteCommandService.changeQuantity(userId, roomId, request.productId(), request.quantity());
        } catch (BusinessException e) {
            return reject(roomId, userId, QuoteEditSource.BUTTON, e);
        }
        countEdit(QuoteEditSource.BUTTON, APPLIED);
        return replyCard(roomId, userId, buttonEditReply(quote, request), edited);
    }

    // 견적 카드 대체 후보의 [좋아요]·[빼기]. 카드 버튼 수정(updateQuoteItem)처럼 LLM 없이,
    // 화면이 보던 카드가 지금 견적일 때만 고르고 새 카드와 정해진 문장을 돌려준다
    public ChatMessageResponse chooseSubstitute(Long roomId, Long userId, ChooseSubstituteRequest request) {
        chatRoomService.getOwnedChatRoom(roomId, userId);
        Optional<Quote> current = quoteService.findQuote(roomId);
        Optional<ErrorCode> stale = staleReason(current.map(Quote::quoteId).orElse(null), request.quoteId());
        if (stale.isPresent()) {
            return reject(roomId, userId, QuoteEditSource.SUBSTITUTE, stale.get());
        }
        Quote quote = current.orElseThrow();
        Optional<SubstituteItem> substitute = quote.substitutes().stream()
                .filter(candidate -> candidate.name().equals(request.ingredientName()))
                .findFirst();
        if (substitute.isEmpty()) {
            return rejectCandidate(roomId, userId, QuoteEditSource.SUBSTITUTE);
        }

        Quote chosen;
        try {
            chosen = request.accept()
                    ? quoteCommandService.acceptSubstitute(userId, roomId, request.ingredientName())
                    : quoteCommandService.rejectSubstitute(userId, roomId, request.ingredientName());
        } catch (BusinessException e) {
            // 견적 담당은 없는 후보(같은 후보를 두 번 누름)를 PRODUCT_NOT_FOUND로 알린다
            if (e.getErrorCode() == ErrorCode.PRODUCT_NOT_FOUND) {
                return rejectCandidate(roomId, userId, QuoteEditSource.SUBSTITUTE);
            }
            return reject(roomId, userId, QuoteEditSource.SUBSTITUTE, e);
        }
        countEdit(QuoteEditSource.SUBSTITUTE, APPLIED);
        return replyCard(roomId, userId, substituteReply(quote, chosen, substitute.get(), request.accept()), chosen);
    }

    // 예산을 넘은 견적 카드의 더 싼 후보 [바꾸기]. 대체 후보 버튼(chooseSubstitute)과 같은 순서로,
    // 화면이 보던 카드가 지금 견적일 때만 상품 하나만 바꾸고 새 카드와 정해진 문장을 돌려준다
    public ChatMessageResponse chooseBudgetOption(Long roomId, Long userId, ChooseBudgetOptionRequest request) {
        chatRoomService.getOwnedChatRoom(roomId, userId);
        Optional<Quote> current = quoteService.findQuote(roomId);
        Optional<ErrorCode> stale = staleReason(current.map(Quote::quoteId).orElse(null), request.quoteId());
        if (stale.isPresent()) {
            return reject(roomId, userId, QuoteEditSource.BUDGET, stale.get());
        }
        Quote quote = current.orElseThrow();
        Optional<BudgetOption> option = quote.budgetOptions().stream()
                .filter(candidate -> candidate.productId().equals(request.productId()))
                .findFirst();
        if (option.isEmpty()) {
            return rejectCandidate(roomId, userId, QuoteEditSource.BUDGET);
        }

        Quote changed;
        try {
            changed = quoteCommandService.applyBudgetOption(userId, roomId, request.productId());
        } catch (BusinessException e) {
            // 견적 담당은 없는 후보(같은 후보를 두 번 누름)를 PRODUCT_NOT_FOUND로, 그사이 모자란 재고를 INSUFFICIENT_STOCK으로 알린다
            return switch (e.getErrorCode()) {
                case PRODUCT_NOT_FOUND -> rejectCandidate(roomId, userId, QuoteEditSource.BUDGET);
                case INSUFFICIENT_STOCK -> {
                    countEdit(QuoteEditSource.BUDGET, ErrorCode.INSUFFICIENT_STOCK.name());
                    yield replyText(roomId, userId,
                            BUDGET_OPTION_SOLD_OUT.formatted(QuoteExplainPrompt.productLabel(option.get().item())));
                }
                default -> reject(roomId, userId, QuoteEditSource.BUDGET, e);
            };
        }
        countEdit(QuoteEditSource.BUDGET, APPLIED);
        return replyCard(roomId, userId, budgetOptionReply(quote, changed, option.get()), changed);
    }

    // LLM이 세운 수정 계획을 순서대로 적용한다. 계획을 세우는 동안(LLM, 수 초) 같은 방의 견적이 바뀌었을 수 있어,
    // 계획 때 본 견적(quoteId)이 아직 현재 견적일 때만 고친다. 수정마다 새 견적 ID로 저장되므로 마지막 견적을 결과로 준다
    public QuoteEditResult applyEditPlan(Long userId, Long roomId, Quote quote, QuoteEditPlan editPlan) {
        String currentQuoteId = quoteService.findQuote(roomId).map(Quote::quoteId).orElse(null);
        Optional<ErrorCode> stale = staleReason(currentQuoteId, quote.quoteId());
        if (stale.isPresent()) {
            return new NotEdited(stale.get());
        }

        Quote edited = quote;
        List<QuoteEdit> appliedEdits = new ArrayList<>();
        for (QuoteEdit edit : editPlan.edits()) {
            try {
                edited = applyEdit(userId, roomId, edit);
                appliedEdits.add(edit);
            } catch (BusinessException e) {
                // 앞의 수정이 이미 저장됐으면 오류 종류와 관계없이 그 견적을 보여 주며 못 한 수정을 알린다.
                // 그래야 채팅에 남는 카드가 저장된 견적(새 견적 ID)과 어긋나지 않는다
                if (!appliedEdits.isEmpty()) {
                    return new PartiallyEdited(edited, appliedEdits, edit, e.getErrorCode());
                }
                // 고친 것이 없으면 안내할 수 있는 오류만 결과로 주고, 그 밖의 오류는 그대로 던진다
                if (QuoteEditReply.from(e.getErrorCode()).isEmpty()) {
                    throw e;
                }
                return new NotEdited(e.getErrorCode());
            }
        }
        return new Edited(edited, appliedEdits);
    }

    // 버튼·계획 때 본 견적(quoteId)이 아직 방의 현재 견적인지 보고, 아니면 그 이유를 준다. 현재 견적이 없으면 currentQuoteId는 null이다
    private static Optional<ErrorCode> staleReason(String currentQuoteId, String quoteId) {
        if (currentQuoteId == null) {
            return Optional.of(ErrorCode.QUOTE_EXPIRED);
        }
        if (!currentQuoteId.equals(quoteId)) {
            return Optional.of(ErrorCode.QUOTE_NOT_CURRENT);
        }
        return Optional.empty();
    }

    private Quote applyEdit(Long userId, Long roomId, QuoteEdit edit) {
        return switch (edit.action()) {
            case REMOVE -> quoteCommandService.removeItem(userId, roomId, edit.productId());
            case CHANGE_QUANTITY -> quoteCommandService.changeQuantity(userId, roomId, edit.productId(), edit.quantity());
            case ADD -> quoteCommandService.addItem(userId, roomId, edit.ingredientName(), edit.amount(), edit.unit());
        };
    }

    // 견적 수정 메서드가 던진 오류. 사용자가 다시 하면 되는 오류는 정해진 문장으로 답하고, 그 밖의 오류(Redis 장애 등)는 그대로 던진다
    private ChatMessageResponse reject(Long roomId, Long userId, QuoteEditSource source, BusinessException e) {
        if (QuoteEditReply.from(e.getErrorCode()).isEmpty()) {
            throw e;
        }
        return reject(roomId, userId, source, e.getErrorCode());
    }

    private ChatMessageResponse reject(Long roomId, Long userId, QuoteEditSource source, ErrorCode reason) {
        countEdit(source, reason.name());
        return replyText(roomId, userId, QuoteEditReply.from(reason).orElseThrow().getText());
    }

    // 고른 후보가 지금 견적에 없다(같은 후보를 두 번 누름). 지표는 견적 담당의 오류와 같은 PRODUCT_NOT_FOUND로 센다
    private ChatMessageResponse rejectCandidate(Long roomId, Long userId, QuoteEditSource source) {
        countEdit(source, ErrorCode.PRODUCT_NOT_FOUND.name());
        return replyText(roomId, userId, QuoteEditReply.CANDIDATE_NOT_FOUND.getText());
    }

    private ChatMessageResponse replyText(Long roomId, Long userId, String text) {
        return ChatMessageResponse.from(chatMessageService.createAssistantMessage(roomId, userId, text));
    }

    // 마지막 상품을 빼고 남은 후보도 없으면 고를 것도 주문할 것도 없어 카드 없이 알린다
    private ChatMessageResponse replyCard(Long roomId, Long userId, String text, Quote quote) {
        if (!QuoteCardPayload.isShowable(quote)) {
            return replyText(roomId, userId, text + NO_ITEM_LEFT);
        }
        return ChatMessageResponse.fromNewQuoteCard(
                chatMessageService.createQuoteCardMessage(roomId, userId, text, QuoteCardPayload.from(quote)));
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

    // 버튼으로 견적 상품을 수정한 뒤, 채팅에 보여줄 안내 문장을 만듦
    private static String buttonEditReply(Quote quote, UpdateQuoteItemRequest request) {
        String label = quote.items().stream()
                .filter(item -> item.productId().equals(request.productId()))
                .findFirst()
                .map(QuoteExplainPrompt::productLabel)
                .orElseThrow();
        return request.isRemove()
                ? ITEM_REMOVED.formatted(label)
                : ITEM_QUANTITY_CHANGED.formatted(label, request.quantity());
    }

    // 견적 수정이 어디서 왔고(source) 어떻게 끝났는지(result: APPLIED 또는 거절 이유) 센다
    private void countEdit(QuoteEditSource source, String result) {
        businessMetrics.count(BusinessMetric.CHAT_QUOTE_EDIT, "source", source.name(), "result", result);
    }
}
