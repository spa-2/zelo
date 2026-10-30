package com.server.zelo.domain.chat.service;

import com.server.zelo.ai.gateway.LlmGateway;
import com.server.zelo.ai.gateway.LlmReply;
import com.server.zelo.ai.gateway.LlmTimeBudget;
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
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.model.QuoteEditReply;
import com.server.zelo.domain.chat.model.QuoteEditResult;
import com.server.zelo.domain.chat.model.QuoteEditResult.Edited;
import com.server.zelo.domain.chat.model.QuoteEditResult.NotEdited;
import com.server.zelo.domain.chat.model.QuoteEditResult.PartiallyEdited;
import com.server.zelo.domain.chat.model.QuoteEditSource;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.service.QuoteService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

// 사용자 메시지 하나에 LLM으로 답한다. 메뉴 계획으로 의도를 판단해 견적 발급·견적 수정·일반 질문으로 나누고,
// 견적을 바꾸는 구간만 ChatQuoteFacade의 방 단위 락 안에서 한다.
// @Transactional을 걸지 않는다: 전체를 한 트랜잭션으로 묶으면 LLM 응답을 기다리는 동안(재시도 포함 약 10초) DB 커넥션을 붙잡아
// 커넥션 풀(10개)이 채팅 요청에 묶이고 주문·결제까지 멈출 수 있다. 저장은 ChatMessageService의 짧은 트랜잭션으로 나눈다
@Service
@RequiredArgsConstructor
public class ChatReplyCommandService {

    // 견적은 발급됐는데 안내 문장만 받지 못했을 때 쓴다. 상품·가격은 카드에 있으므로 장보기는 이어 갈 수 있다
    private static final String QUOTE_READY = "견적을 준비했어요. 내용을 확인해 주세요.";
    // 지표의 결과 태그 값. 실패는 오류 코드(AI_xxx)나 ErrorCode 이름을 그대로 쓴다
    private static final String SUCCESS = "SUCCESS";
    private static final String APPLIED = "APPLIED";
    private static final String PARTIAL = "PARTIAL";
    private static final String BUSY = "BUSY";

    private final ChatPrompt chatPrompt;
    private final LlmGateway llmGateway;
    private final QuoteService quoteService;
    private final LlmTimeBudget llmTimeBudget;
    private final MenuPlanPrompt menuPlanPrompt;
    private final ProductService productService;
    private final ChatQuoteFacade chatQuoteFacade;
    private final BusinessMetrics businessMetrics;
    private final QuoteEditPrompt quoteEditPrompt;
    private final ChatMessageService chatMessageService;
    private final QuoteExplainPrompt quoteExplainPrompt;

    // 메시지가 장보기 요청인지, 견적 수정인지, 일반 질문인지를 메뉴 계획으로 먼저 판단하고 서버가 길을 나눈다.
    // 견적 발급·수정은 LLM이 아니라 서버가 직접 부른다(매번 같은 순서라 예측·테스트할 수 있고, LLM 재시도와 얽히지 않는다)
    // 메시지 하나를 답하는 데 걸린 전체 시간(LLM 여러 번 + 견적 발급·수정 + 저장)을 의도·결과별로 남긴다.
    // 의도를 정하기 전에 실패하면 intent=UNKNOWN이다.
    // toResponse: AI 메시지를 응답 모양(T)으로 바꾼다. LLM 실패 응답의 data도 같은 모양으로 만든다
    // 이 메시지 안의 LLM 호출(메뉴 계획·수정 계획·안내)은 한 마감 시각(LlmTimeBudget)을 나눠 써서, 몇 번을 부르든 정해진 시간 안에 응답한다.
    // 시간이 모자라 받지 못한 답은 다른 LLM 실패와 같은 길(고정 문구, 안내 문장만 실패하면 카드 + 고정 문장)로 간다
    public <T> T replyTo(Long roomId, Long userId, List<ChatMessage> history, String text,
                         Function<ChatMessageResponse, T> toResponse) {
        return llmTimeBudget.run(() -> answer(roomId, userId, history, text, toResponse));
    }

    private <T> T answer(Long roomId, Long userId, List<ChatMessage> history, String text,
                         Function<ChatMessageResponse, T> toResponse) {
        long start = System.nanoTime();
        String intent = "UNKNOWN";
        String outcome = SUCCESS;
        try {
            // 재료 목록은 요청마다 재료 테이블에서 읽어, 프롬프트와 답의 단위 검증이 같은 목록을 쓰게 한다
            Map<String, BaseUnit> ingredientUnits = productService.findIngredientUnits();
            // 담은 상품도 대체 후보도 없는 견적은 고칠 것도 주문할 것도 없어서 견적이 없는 것으로 본다
            Optional<Quote> currentQuote = quoteService.findQuote(roomId).filter(QuoteCardPayload::isShowable);
            String currentMenuName = currentQuote.map(Quote::menuName).orElse(null);
            MenuPlan menuPlan = planMenu(roomId, userId, history, text, ingredientUnits, currentMenuName, toResponse);
            intent = menuPlan.intent().name();
            ChatMessageResponse reply = switch (menuPlan.intent()) {
                case SHOPPING_REQUEST -> replyWithQuote(roomId, userId, menuPlan, toResponse);
                // 현재 견적이 "없음"이면 프롬프트가 QUOTE_EDIT를 막지만, LLM 답을 믿지 않고 한 번 더 본다
                case QUOTE_EDIT -> currentQuote
                        .map(quote -> editQuote(roomId, userId, history, text, ingredientUnits, quote, toResponse))
                        .orElseGet(() -> rejectEdit(roomId, userId, ErrorCode.QUOTE_EXPIRED.name(),
                                QuoteEditReply.NO_QUOTE_TO_EDIT.getText()));
                case GENERAL_QUESTION -> replyText(roomId, userId,
                        explain(roomId, userId, chatPrompt.systemPrompt(), history, text, toResponse).text());
            };
            return toResponse.apply(reply);
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
    private <T> ChatMessageResponse replyWithQuote(Long roomId, Long userId, MenuPlan menuPlan,
                                                   Function<ChatMessageResponse, T> toResponse) {
        Optional<Quote> created = unlessQuoteBusy(() -> chatQuoteFacade.createQuote(userId, roomId, menuPlan));
        if (created.isEmpty()) {
            return replyText(roomId, userId, QuoteEditReply.QUOTE_IN_PROGRESS.getText());
        }
        Quote quote = created.get();
        return replyWithQuoteCard(roomId, userId, quote, quoteExplainPrompt.userText(menuPlan, quote), toResponse);
    }

    // 무엇을 고칠지는 LLM이 수정 계획으로 정하고, 실제 수정은 LLM 호출이 끝난 뒤 서버가 한다.
    // 그래서 LLM 재시도·시간 초과 때문에 견적이 두 번 고쳐지거나, 고쳐진 채로 실패 응답이 나가지 않는다.
    // 계획 검사(되물을지, 견적에 있는 상품인지)는 락이 필요 없어 먼저 하고, 적용만 락 안에서 한다.
    // 안내 문장(LLM)과 메시지 저장은 락이 풀린 뒤에 한다
    private <T> ChatMessageResponse editQuote(Long roomId, Long userId, List<ChatMessage> history, String text,
                                              Map<String, BaseUnit> ingredientUnits, Quote quote,
                                              Function<ChatMessageResponse, T> toResponse) {
        QuoteEditPlan editPlan = planQuoteEdit(roomId, userId, history, text, ingredientUnits, quote, toResponse);
        if (editPlan.edits().isEmpty()) {
            String question = StringUtils.hasText(editPlan.question())
                    ? editPlan.question()
                    : QuoteEditReply.ASK_EDIT_AGAIN.getText();
            return rejectEdit(roomId, userId, "ASK_AGAIN", question);
        }
        // 하나라도 현재 견적에 없는 상품을 가리키면 아무것도 고치지 않아, 견적이 일부만 바뀌지 않게 한다
        if (!editPlan.isApplicableTo(quote)) {
            return rejectEdit(roomId, userId, "NOT_IN_QUOTE", QuoteEditReply.NOT_IN_QUOTE.getText());
        }

        Optional<QuoteEditResult> applied =
                unlessQuoteBusy(() -> chatQuoteFacade.applyEditPlan(userId, roomId, quote, editPlan));
        if (applied.isEmpty()) {
            return rejectEdit(roomId, userId, BUSY, QuoteEditReply.QUOTE_IN_PROGRESS.getText());
        }
        return switch (applied.get()) {
            case Edited edited -> {
                countEdit(APPLIED);
                yield replyWithQuoteCard(roomId, userId, edited.quote(),
                        quoteExplainPrompt.editedText(quote, edited.quote(), edited.appliedEdits(), null, null),
                        toResponse);
            }
            case PartiallyEdited partial -> {
                countEdit(PARTIAL);
                yield replyWithQuoteCard(roomId, userId, partial.quote(),
                        quoteExplainPrompt.editedText(quote, partial.quote(), partial.appliedEdits(),
                                partial.failedEdit(), partial.failure()),
                        toResponse);
            }
            case NotEdited notEdited -> rejectEdit(roomId, userId, notEdited.reason().name(),
                    QuoteEditReply.from(notEdited.reason()).orElseThrow().getText());
        };
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

    // 같은 방의 다른 발급·수정이 락을 3초 넘게 잡고 있으면 빈 값이다. 부르는 쪽은 잠시 후 다시 말하라고 답한다
    private <R> Optional<R> unlessQuoteBusy(Supplier<R> quoteChange) {
        try {
            return Optional.of(quoteChange.get());
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.TIMEOUT_EXCEPTION) {
                throw e;
            }
            businessMetrics.count(BusinessMetric.CHAT_QUOTE_BUSY);
            return Optional.empty();
        }
    }

    private <T> ChatMessageResponse replyWithQuoteCard(Long roomId, Long userId, Quote quote, String quoteText,
                                                       Function<ChatMessageResponse, T> toResponse) {
        if (!QuoteCardPayload.isShowable(quote)) {
            // 담은 상품도 고를 후보도 없으면 카드 없이 빠진 이유만 안내한다. 후보만 있으면 고를 수 있게 카드를 저장한다
            String reason = explain(roomId, userId, quoteExplainPrompt.systemPrompt(), List.of(), quoteText, toResponse).text();
            return replyText(roomId, userId, reason);
        }
        return ChatMessageResponse.fromNewQuoteCard(chatMessageService.createQuoteCardMessage(
                roomId, userId, explainQuote(quoteText), QuoteCardPayload.from(quote)));
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

    // 말로 고치지 못한 이유를 세고, 정해진 문장(또는 LLM이 되물은 문장)으로 답한다
    private ChatMessageResponse rejectEdit(Long roomId, Long userId, String result, String text) {
        countEdit(result);
        return replyText(roomId, userId, text);
    }

    private ChatMessageResponse replyText(Long roomId, Long userId, String text) {
        return ChatMessageResponse.from(chatMessageService.createAssistantMessage(roomId, userId, text));
    }

    // 말로 고친 견적 수정이 어떻게 끝났는지(result: APPLIED, PARTIAL, BUSY, 거절 이유) 센다
    private void countEdit(String result) {
        businessMetrics.count(BusinessMetric.CHAT_QUOTE_EDIT, "source", QuoteEditSource.CHAT.name(), "result", result);
    }
}
