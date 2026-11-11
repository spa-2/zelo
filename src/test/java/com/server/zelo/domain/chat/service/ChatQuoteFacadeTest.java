package com.server.zelo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.lock.LockDistributeService;
import com.server.zelo.common.lock.LockKey;
import com.server.zelo.domain.chat.dto.request.ChooseBudgetOptionRequest;
import com.server.zelo.domain.chat.dto.request.ChooseSubstituteRequest;
import com.server.zelo.domain.chat.dto.request.UpdateQuoteItemRequest;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.model.QuoteEditResult;
import com.server.zelo.domain.chat.model.QuoteEditResult.NotEdited;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatQuoteFacadeTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);
    private static final Quote QUOTE = new Quote("quote-1", 1L, 10L, "김치볶음밥", List.of(), List.of(), 0L, NOW, NOW.plusMinutes(10));
    private static final ChatMessageResponse REPLY =
            new ChatMessageResponse(42L, ChatSender.ASSISTANT, "백김치 (1kg) 상품을 뺐어요.", null, null, false, NOW);

    @Mock
    private QuoteCommandService quoteCommandService;

    @Mock
    private LockDistributeService lockDistributeService;

    @Mock
    private ChatQuoteCommandService chatQuoteCommandService;

    @InjectMocks
    private ChatQuoteFacade chatQuoteFacade;

    // 락을 잡은 키를 기록하고, 넘긴 작업이 그 락 안에서 실행됐는지 볼 수 있게 실행 중인 키를 남긴다
    private final AtomicReference<String> lockedKey = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        lenient().when(lockDistributeService.execute(anyString(), ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> {
                    lockedKey.set(invocation.getArgument(0));
                    try {
                        return invocation.<Supplier<?>>getArgument(1).get();
                    } finally {
                        lockedKey.set(null);
                    }
                });
    }

    @Test
    void 견적_발급은_방_단위_락_안에서_한다() {
        // given
        MenuPlan menuPlan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 4, null, List.of(), List.of(), List.of());
        given(quoteCommandService.createQuote(1L, 10L, menuPlan)).willAnswer(invocation -> {
            assertThat(lockedKey.get()).isEqualTo(LockKey.quote(10L));
            return QUOTE;
        });

        // when
        Quote created = chatQuoteFacade.createQuote(1L, 10L, menuPlan);

        // then
        assertThat(created).isSameAs(QUOTE);
    }

    @Test
    void 말로_고친_계획의_적용은_방_단위_락_안에서_한다() {
        // given
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(), "");
        QuoteEditResult notEdited = new NotEdited(ErrorCode.QUOTE_NOT_CURRENT);
        given(chatQuoteCommandService.applyEditPlan(1L, 10L, QUOTE, editPlan)).willAnswer(invocation -> {
            assertThat(lockedKey.get()).isEqualTo(LockKey.quote(10L));
            return notEdited;
        });

        // when
        QuoteEditResult result = chatQuoteFacade.applyEditPlan(1L, 10L, QUOTE, editPlan);

        // then
        assertThat(result).isSameAs(notEdited);
    }

    @Test
    void 카드_버튼_3종은_확인부터_메시지_저장까지_방_단위_락_안에서_한다() {
        // given
        UpdateQuoteItemRequest updateRequest = new UpdateQuoteItemRequest("quote-1", 1L, 0);
        ChooseSubstituteRequest substituteRequest = new ChooseSubstituteRequest("quote-1", "대파", true);
        ChooseBudgetOptionRequest budgetRequest = new ChooseBudgetOptionRequest("quote-1", 1L);
        given(chatQuoteCommandService.updateQuoteItem(10L, 1L, updateRequest)).willAnswer(invocation -> lockedReply());
        given(chatQuoteCommandService.chooseSubstitute(10L, 1L, substituteRequest)).willAnswer(invocation -> lockedReply());
        given(chatQuoteCommandService.chooseBudgetOption(10L, 1L, budgetRequest)).willAnswer(invocation -> lockedReply());

        // when & then
        assertThat(chatQuoteFacade.updateQuoteItem(10L, 1L, updateRequest)).isSameAs(REPLY);
        assertThat(chatQuoteFacade.chooseSubstitute(10L, 1L, substituteRequest)).isSameAs(REPLY);
        assertThat(chatQuoteFacade.chooseBudgetOption(10L, 1L, budgetRequest)).isSameAs(REPLY);
    }

    @Test
    void 락을_3초_안에_얻지_못하면_견적을_고치지_않고_409_예외를_그대로_던진다() {
        // given: 같은 방의 다른 요청이 락을 잡고 있다
        given(lockDistributeService.execute(eq(LockKey.quote(10L)), ArgumentMatchers.<Supplier<Object>>any()))
                .willThrow(new BusinessException(ErrorCode.TIMEOUT_EXCEPTION));

        // when & then
        assertThatThrownBy(() -> chatQuoteFacade.updateQuoteItem(10L, 1L, new UpdateQuoteItemRequest("quote-1", 1L, 0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TIMEOUT_EXCEPTION));
        then(chatQuoteCommandService).shouldHaveNoInteractions();
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    private ChatMessageResponse lockedReply() {
        assertThat(lockedKey.get()).isEqualTo(LockKey.quote(10L));
        return REPLY;
    }
}
