package com.server.zelo.domain.chat.service;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.chat.dto.request.ChooseBudgetOptionRequest;
import com.server.zelo.domain.chat.dto.request.ChooseSubstituteRequest;
import com.server.zelo.domain.chat.dto.request.UpdateQuoteItemRequest;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.model.QuoteEditResult;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 견적은 채팅방마다 하나(Redis)라, 같은 방의 발급·수정이 "현재 견적 확인"과 "적용" 사이에 끼어들지 못하게 방 단위로 잠근다.
// 락 안에서는 LLM을 부르지 않는다(같은 방의 다음 요청이 락 대기 3초를 넘기지 않게). 그래서 말로 고칠 때는
// ChatReplyCommandService가 LLM으로 계획을 세운 뒤 적용 구간만 이 Facade로 잠근다(WebhookCommandService → PaymentFacade와 같은 형태).
// 3초 안에 락을 얻지 못하면 TIMEOUT_EXCEPTION(409)을 그대로 던진다
@Component
@RequiredArgsConstructor
public class ChatQuoteFacade {

    private final QuoteCommandService quoteCommandService;
    private final LockDistributeService lockDistributeService;
    private final ChatQuoteCommandService chatQuoteCommandService;

    // 발급도 방의 견적을 덮어쓰므로, 같은 방의 수정이 확인과 적용 사이에 있으면 끝날 때까지 기다린다
    public Quote createQuote(Long userId, Long roomId, MenuPlan menuPlan) {
        return lockDistributeService.execute(LockKey.quote(roomId),
                () -> quoteCommandService.createQuote(userId, roomId, menuPlan));
    }

    public QuoteEditResult applyEditPlan(Long userId, Long roomId, Quote quote, QuoteEditPlan editPlan) {
        return lockDistributeService.execute(LockKey.quote(roomId),
                () -> chatQuoteCommandService.applyEditPlan(userId, roomId, quote, editPlan));
    }

    // 카드 버튼은 LLM을 부르지 않아, 확인부터 카드 메시지 저장까지 전부 락 안에서 한다
    public ChatMessageResponse updateQuoteItem(Long roomId, Long userId, UpdateQuoteItemRequest request) {
        return lockDistributeService.execute(LockKey.quote(roomId),
                () -> chatQuoteCommandService.updateQuoteItem(roomId, userId, request));
    }

    public ChatMessageResponse chooseSubstitute(Long roomId, Long userId, ChooseSubstituteRequest request) {
        return lockDistributeService.execute(LockKey.quote(roomId),
                () -> chatQuoteCommandService.chooseSubstitute(roomId, userId, request));
    }

    public ChatMessageResponse chooseBudgetOption(Long roomId, Long userId, ChooseBudgetOptionRequest request) {
        return lockDistributeService.execute(LockKey.quote(roomId),
                () -> chatQuoteCommandService.chooseBudgetOption(roomId, userId, request));
    }
}
