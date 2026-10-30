package com.server.zelo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.ai.plan.QuoteEditAction;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.chat.dto.request.ChooseBudgetOptionRequest;
import com.server.zelo.domain.chat.dto.request.ChooseSubstituteRequest;
import com.server.zelo.domain.chat.dto.request.UpdateQuoteItemRequest;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.dto.response.QuoteCardStatus;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.model.QuoteEditResult;
import com.server.zelo.domain.chat.model.QuoteEditResult.Edited;
import com.server.zelo.domain.chat.model.QuoteEditResult.NotEdited;
import com.server.zelo.domain.chat.model.QuoteEditResult.PartiallyEdited;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.quote.model.BudgetOption;
import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.model.SubstituteItem;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import com.server.zelo.domain.quote.service.QuoteService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

// 카드 버튼(✕, −/+, 대체 후보, 예산 후보)과 말로 고친 계획의 적용. 락은 ChatQuoteFacade가 걸고, 여기서는 락 안에서 할 일만 본다
@ExtendWith(MockitoExtension.class)
class ChatQuoteCommandServiceTest {

    private static final QuoteItem KIMCHI_ITEM = new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 1, 12_000L);
    private static final QuoteItem EGG_ITEM = new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 1, 4_500L);
    private static final QuoteItem HAM_ITEM = new QuoteItem(3L, "MEA-0040", "햄", "200g", 3_900L, 1, 3_900L);
    private static final QuoteItem LEEK_ITEM = new QuoteItem(5L, "VEG-0102", "수입 대파", "1단", 2_500L, 1, 2_500L);
    private static final SubstituteItem LEEK_SUBSTITUTE = new SubstituteItem("대파", "국산 대파", LEEK_ITEM);
    // 백김치(12,000원) 대신 담을 수 있는 더 싼 후보 (7,800원)
    private static final QuoteItem CHEAPER_KIMCHI_ITEM = new QuoteItem(6L, "SID-0009", "순한 백김치", "500g", 3_900L, 2, 7_800L);
    private static final BudgetOption KIMCHI_BUDGET_OPTION = new BudgetOption(1L, CHEAPER_KIMCHI_ITEM);
    private static final String CANDIDATE_NOT_FOUND = "이미 고른 후보예요. 최신 견적 카드를 확인해 주세요.";
    private static final String NO_QUOTE_TO_EDIT = "고칠 견적이 없어요. 견적이 만료됐을 수 있으니 만들 메뉴를 다시 말씀해 주세요.";
    private static final String NOT_IN_QUOTE = "견적 카드에 없는 상품이라 고치지 못했어요. 카드에 있는 상품 이름으로 다시 말씀해 주세요.";
    private static final String NOT_ENOUGH_STOCK = "재고가 부족해서 그 수량으로 바꿀 수 없어요. 수량을 줄여서 다시 말씀해 주세요.";
    private static final String QUOTE_CHANGED = "견적이 방금 바뀌어서 고치지 않았어요. 최신 견적 카드를 확인하고 다시 말씀해 주세요.";

    @Mock
    private QuoteService quoteService;

    @Mock
    private QuoteCommandService quoteCommandService;

    @Mock
    private ChatRoomService chatRoomService;

    @Mock
    private ChatMessageService chatMessageService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);

    @InjectMocks
    private ChatQuoteCommandService chatQuoteCommandService;

    @Test
    void 카드의_빼기_버튼은_방_주인을_확인한_뒤_상품을_빼고_새_카드와_정해진_문장을_저장한다() {
        // given
        Quote removed = quote("quote-2", List.of(EGG_ITEM));
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of())));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willReturn(removed);
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "백김치 (1kg) 상품을 뺐어요.", QuoteCardPayload.from(removed)))
                .willReturn(cardMessage("백김치 (1kg) 상품을 뺐어요."));

        // when
        ChatMessageResponse response = chatQuoteCommandService.updateQuoteItem(10L, 1L,
                new UpdateQuoteItemRequest("quote-1", 1L, 0));

        // then: 방금 만든 카드는 방의 현재 견적이라 ACTIVE다
        InOrder order = inOrder(chatRoomService, quoteCommandService);
        order.verify(chatRoomService).getOwnedChatRoom(10L, 1L);
        order.verify(quoteCommandService).removeItem(1L, 10L, 1L);
        assertThat(response.content()).isEqualTo("백김치 (1kg) 상품을 뺐어요.");
        assertThat(response.quoteCardStatus()).isEqualTo(QuoteCardStatus.ACTIVE);
        assertThat(counter("zelo.chat.quote.edit", "source", "BUTTON", "result", "APPLIED")).isEqualTo(1);
    }

    @Test
    void 카드의_수량_버튼은_최종_수량으로_바꾸고_정해진_문장으로_알린다() {
        // given
        QuoteItem threeEggs = new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 3, 13_500L);
        Quote changed = quote("quote-2", List.of(KIMCHI_ITEM, threeEggs));
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of())));
        given(quoteCommandService.changeQuantity(1L, 10L, 2L, 3)).willReturn(changed);
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "계란 (10구) 상품을 3개로 바꿨어요.", QuoteCardPayload.from(changed)))
                .willReturn(cardMessage("계란 (10구) 상품을 3개로 바꿨어요."));

        // when
        ChatMessageResponse response = chatQuoteCommandService.updateQuoteItem(10L, 1L,
                new UpdateQuoteItemRequest("quote-1", 2L, 3));

        // then
        assertThat(response.content()).isEqualTo("계란 (10구) 상품을 3개로 바꿨어요.");
        then(quoteCommandService).should(never()).removeItem(any(), any(), any());
    }

    @Test
    void 마지막_상품을_빼면_카드_없이_남은_상품이_없다고_알린다() {
        // given
        String reply = "백김치 (1kg) 상품을 뺐어요. 견적에 남은 상품이 없어요. 만들 메뉴를 다시 말씀해 주세요.";
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote(List.of(KIMCHI_ITEM), List.of())));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willReturn(quote("quote-2", List.of()));
        givenAssistantMessage(reply);

        // when
        ChatMessageResponse response = chatQuoteCommandService.updateQuoteItem(10L, 1L,
                new UpdateQuoteItemRequest("quote-1", 1L, 0));

        // then
        assertThat(response.content()).isEqualTo(reply);
        assertThat(response.quoteCardStatus()).isNull();
        then(chatMessageService).should(never()).createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void 카드_버튼으로_마지막_상품을_빼도_후보가_남으면_카드를_저장한다() {
        // given
        Quote removed = quoteWithSubstitutes("quote-2", List.of());
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quoteWithSubstitutes("quote-1", List.of(KIMCHI_ITEM))));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willReturn(removed);
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "백김치 (1kg) 상품을 뺐어요.", QuoteCardPayload.from(removed)))
                .willReturn(cardMessage("백김치 (1kg) 상품을 뺐어요."));

        // when
        ChatMessageResponse response = chatQuoteCommandService.updateQuoteItem(10L, 1L,
                new UpdateQuoteItemRequest("quote-1", 1L, 0));

        // then
        assertThat(response.content()).isEqualTo("백김치 (1kg) 상품을 뺐어요.");
        assertThat(response.quoteCardStatus()).isEqualTo(QuoteCardStatus.ACTIVE);
    }

    @Test
    void 버튼을_누른_카드가_지금_견적이_아니면_고치지_않고_정해진_문장으로_답한다() {
        // given: 화면은 quote-1 카드를 보고 눌렀지만 지금 견적은 quote-9다
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote("quote-9", List.of(KIMCHI_ITEM))));
        givenAssistantMessage(QUOTE_CHANGED);

        // when
        ChatMessageResponse response = chatQuoteCommandService.updateQuoteItem(10L, 1L,
                new UpdateQuoteItemRequest("quote-1", 1L, 0));

        // then
        assertThat(response.content()).isEqualTo(QUOTE_CHANGED);
        assertThat(counter("zelo.chat.quote.edit", "source", "BUTTON", "result", "QUOTE_NOT_CURRENT")).isEqualTo(1);
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 버튼을_눌렀을_때_견적이_만료됐으면_고치지_않고_정해진_문장으로_답한다() {
        // given
        given(quoteService.findQuote(10L)).willReturn(Optional.empty());
        givenAssistantMessage(NO_QUOTE_TO_EDIT);

        // when
        ChatMessageResponse response = chatQuoteCommandService.updateQuoteItem(10L, 1L,
                new UpdateQuoteItemRequest("quote-1", 1L, 0));

        // then
        assertThat(response.content()).isEqualTo(NO_QUOTE_TO_EDIT);
        assertThat(counter("zelo.chat.quote.edit", "source", "BUTTON", "result", "QUOTE_EXPIRED")).isEqualTo(1);
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 버튼으로_바꾼_수량만큼_재고가_없으면_고치지_않고_정해진_문장으로_답한다() {
        // given
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote(List.of(KIMCHI_ITEM), List.of())));
        given(quoteCommandService.changeQuantity(1L, 10L, 1L, 10))
                .willThrow(new BusinessException(ErrorCode.INSUFFICIENT_STOCK));
        givenAssistantMessage(NOT_ENOUGH_STOCK);

        // when
        ChatMessageResponse response = chatQuoteCommandService.updateQuoteItem(10L, 1L,
                new UpdateQuoteItemRequest("quote-1", 1L, 10));

        // then
        assertThat(response.content()).isEqualTo(NOT_ENOUGH_STOCK);
        assertThat(counter("zelo.chat.quote.edit", "source", "BUTTON", "result", "INSUFFICIENT_STOCK")).isEqualTo(1);
        then(chatMessageService).should(never()).createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void 견적에_없는_상품의_버튼이면_고치지_않고_정해진_문장으로_답한다() {
        // given: 이미 뺀 상품의 ✕를 한 번 더 누른 경우
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote(List.of(EGG_ITEM), List.of())));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willThrow(new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        givenAssistantMessage(NOT_IN_QUOTE);

        // when
        ChatMessageResponse response = chatQuoteCommandService.updateQuoteItem(10L, 1L,
                new UpdateQuoteItemRequest("quote-1", 1L, 0));

        // then
        assertThat(response.content()).isEqualTo(NOT_IN_QUOTE);
    }

    @Test
    void 버튼_수정_중_안내할_수_없는_오류는_문장으로_바꾸지_않고_그대로_던진다() {
        // given: Redis 장애처럼 사용자가 다시 눌러도 해결되지 않는 오류
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote(List.of(KIMCHI_ITEM), List.of())));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> chatQuoteCommandService.updateQuoteItem(10L, 1L, new UpdateQuoteItemRequest("quote-1", 1L, 0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        then(chatMessageService).shouldHaveNoInteractions();
    }

    @Test
    void 다른_사용자의_방이면_견적을_보지도_고치지도_않는다() {
        // given
        given(chatRoomService.getOwnedChatRoom(10L, 2L)).willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> chatQuoteCommandService.updateQuoteItem(10L, 2L, new UpdateQuoteItemRequest("quote-1", 1L, 0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
        then(quoteService).shouldHaveNoInteractions();
        then(quoteCommandService).shouldHaveNoInteractions();
        then(chatMessageService).shouldHaveNoInteractions();
    }

    @Test
    void 후보의_좋아요는_방_주인을_확인한_뒤_담고_새_카드와_정해진_문장을_저장한다() {
        // given
        Quote accepted = quote("quote-2", List.of(KIMCHI_ITEM, LEEK_ITEM));
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quoteWithSubstitutes("quote-1", List.of(KIMCHI_ITEM))));
        given(quoteCommandService.acceptSubstitute(1L, 10L, "대파")).willReturn(accepted);
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "수입 대파 (1단) 상품을 담았어요.", QuoteCardPayload.from(accepted)))
                .willReturn(cardMessage("수입 대파 (1단) 상품을 담았어요."));

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseSubstitute(10L, 1L,
                new ChooseSubstituteRequest("quote-1", "대파", true));

        // then
        InOrder order = inOrder(chatRoomService, quoteCommandService);
        order.verify(chatRoomService).getOwnedChatRoom(10L, 1L);
        order.verify(quoteCommandService).acceptSubstitute(1L, 10L, "대파");
        assertThat(response.content()).isEqualTo("수입 대파 (1단) 상품을 담았어요.");
        assertThat(response.quoteCardStatus()).isEqualTo(QuoteCardStatus.ACTIVE);
        assertThat(counter("zelo.chat.quote.edit", "source", "SUBSTITUTE", "result", "APPLIED")).isEqualTo(1);
    }

    @Test
    void 후보의_빼기는_그_재료를_빼고_새_카드로_알린다() {
        // given
        Quote rejected = quote("quote-2", List.of(KIMCHI_ITEM));
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quoteWithSubstitutes("quote-1", List.of(KIMCHI_ITEM))));
        given(quoteCommandService.rejectSubstitute(1L, 10L, "대파")).willReturn(rejected);
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "대파 재료는 뺐어요.", QuoteCardPayload.from(rejected)))
                .willReturn(cardMessage("대파 재료는 뺐어요."));

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseSubstitute(10L, 1L,
                new ChooseSubstituteRequest("quote-1", "대파", false));

        // then
        assertThat(response.content()).isEqualTo("대파 재료는 뺐어요.");
        then(quoteCommandService).should(never()).acceptSubstitute(any(), any(), any());
    }

    @Test
    void 좋아요를_누르는_사이_후보도_품절되면_그_재료는_뺐다고_알린다() {
        // given: 견적 담당은 품절이면 담지 않고 빠진 재료에 OUT_OF_STOCK으로 넣는다
        Quote soldOut = quote("quote-2", List.of(KIMCHI_ITEM), List.of(new ExcludedItem("대파", "OUT_OF_STOCK")));
        String reply = "수입 대파 (1단) 상품도 방금 품절돼서 대파 재료는 뺐어요.";
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quoteWithSubstitutes("quote-1", List.of(KIMCHI_ITEM))));
        given(quoteCommandService.acceptSubstitute(1L, 10L, "대파")).willReturn(soldOut);
        given(chatMessageService.createQuoteCardMessage(10L, 1L, reply, QuoteCardPayload.from(soldOut)))
                .willReturn(cardMessage(reply));

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseSubstitute(10L, 1L,
                new ChooseSubstituteRequest("quote-1", "대파", true));

        // then
        assertThat(response.content()).isEqualTo(reply);
    }

    @Test
    void 후보를_빼서_담은_상품도_후보도_없으면_카드_없이_알린다() {
        // given
        String reply = "대파 재료는 뺐어요. 견적에 남은 상품이 없어요. 만들 메뉴를 다시 말씀해 주세요.";
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quoteWithSubstitutes("quote-1", List.of())));
        given(quoteCommandService.rejectSubstitute(1L, 10L, "대파")).willReturn(quote("quote-2", List.of()));
        givenAssistantMessage(reply);

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseSubstitute(10L, 1L,
                new ChooseSubstituteRequest("quote-1", "대파", false));

        // then
        assertThat(response.content()).isEqualTo(reply);
        then(chatMessageService).should(never()).createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void 지난_카드의_후보_버튼이면_고르지_않고_정해진_문장으로_답한다() {
        // given
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quoteWithSubstitutes("quote-9", List.of(KIMCHI_ITEM))));
        givenAssistantMessage(QUOTE_CHANGED);

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseSubstitute(10L, 1L,
                new ChooseSubstituteRequest("quote-1", "대파", true));

        // then
        assertThat(response.content()).isEqualTo(QUOTE_CHANGED);
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 지금_견적에_없는_후보면_이미_고른_후보라고_답한다() {
        // given: 후보 없는 견적
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote(List.of(KIMCHI_ITEM), List.of())));
        givenAssistantMessage(CANDIDATE_NOT_FOUND);

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseSubstitute(10L, 1L,
                new ChooseSubstituteRequest("quote-1", "대파", true));

        // then
        assertThat(response.content()).isEqualTo(CANDIDATE_NOT_FOUND);
        assertThat(counter("zelo.chat.quote.edit", "source", "SUBSTITUTE", "result", "PRODUCT_NOT_FOUND")).isEqualTo(1);
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 견적_담당이_후보가_이미_없다고_하면_이미_고른_후보라고_답한다() {
        // given: 견적 담당은 없는 후보를 PRODUCT_NOT_FOUND로 알린다
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quoteWithSubstitutes("quote-1", List.of(KIMCHI_ITEM))));
        given(quoteCommandService.acceptSubstitute(1L, 10L, "대파")).willThrow(new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        givenAssistantMessage(CANDIDATE_NOT_FOUND);

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseSubstitute(10L, 1L,
                new ChooseSubstituteRequest("quote-1", "대파", true));

        // then
        assertThat(response.content()).isEqualTo(CANDIDATE_NOT_FOUND);
    }

    @Test
    void 후보를_고를_때_견적이_만료됐으면_고르지_않고_정해진_문장으로_답한다() {
        // given
        given(quoteService.findQuote(10L)).willReturn(Optional.empty());
        givenAssistantMessage(NO_QUOTE_TO_EDIT);

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseSubstitute(10L, 1L,
                new ChooseSubstituteRequest("quote-1", "대파", true));

        // then
        assertThat(response.content()).isEqualTo(NO_QUOTE_TO_EDIT);
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 예산_후보의_바꾸기는_방_주인을_확인한_뒤_바꾸고_예산_안에_들어왔다고_알린다() {
        // given: 예산 15,000원에 16,500원을 담았다. 백김치를 바꾸면 12,300원이 된다
        Quote changed = budgetQuote("quote-2", List.of(CHEAPER_KIMCHI_ITEM, EGG_ITEM), 15_000L, List.of());
        String reply = "백김치 (1kg) 대신 순한 백김치 (500g) 상품을 담았어요. 합계 12,300원으로 예산 안에 들어왔어요.";
        given(quoteService.findQuote(10L)).willReturn(Optional.of(overBudgetQuote(15_000L)));
        given(quoteCommandService.applyBudgetOption(1L, 10L, 1L)).willReturn(changed);
        given(chatMessageService.createQuoteCardMessage(10L, 1L, reply, QuoteCardPayload.from(changed)))
                .willReturn(cardMessage(reply));

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseBudgetOption(10L, 1L,
                new ChooseBudgetOptionRequest("quote-1", 1L));

        // then
        InOrder order = inOrder(chatRoomService, quoteCommandService);
        order.verify(chatRoomService).getOwnedChatRoom(10L, 1L);
        order.verify(quoteCommandService).applyBudgetOption(1L, 10L, 1L);
        assertThat(response.content()).isEqualTo(reply);
        assertThat(response.quoteCardStatus()).isEqualTo(QuoteCardStatus.ACTIVE);
        assertThat(counter("zelo.chat.quote.edit", "source", "BUDGET", "result", "APPLIED")).isEqualTo(1);
    }

    @Test
    void 바꿔도_예산을_넘으면_아직_넘는_금액을_알린다() {
        // given: 예산 10,000원이라 바꾼 뒤(12,300원)에도 2,300원 넘는다
        Quote changed = budgetQuote("quote-2", List.of(CHEAPER_KIMCHI_ITEM, EGG_ITEM), 10_000L, List.of());
        String reply = "백김치 (1kg) 대신 순한 백김치 (500g) 상품을 담았어요. 아직 예산보다 2,300원 많아요.";
        given(quoteService.findQuote(10L)).willReturn(Optional.of(overBudgetQuote(10_000L)));
        given(quoteCommandService.applyBudgetOption(1L, 10L, 1L)).willReturn(changed);
        given(chatMessageService.createQuoteCardMessage(10L, 1L, reply, QuoteCardPayload.from(changed)))
                .willReturn(cardMessage(reply));

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseBudgetOption(10L, 1L,
                new ChooseBudgetOptionRequest("quote-1", 1L));

        // then
        assertThat(response.content()).isEqualTo(reply);
    }

    @Test
    void 바꾸는_사이_더_싼_상품_재고가_모자라면_바꾸지_못했다고_알린다() {
        // given
        String reply = "순한 백김치 (500g) 상품 재고가 부족해서 바꾸지 못했어요.";
        given(quoteService.findQuote(10L)).willReturn(Optional.of(overBudgetQuote(15_000L)));
        given(quoteCommandService.applyBudgetOption(1L, 10L, 1L))
                .willThrow(new BusinessException(ErrorCode.INSUFFICIENT_STOCK));
        givenAssistantMessage(reply);

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseBudgetOption(10L, 1L,
                new ChooseBudgetOptionRequest("quote-1", 1L));

        // then
        assertThat(response.content()).isEqualTo(reply);
        assertThat(counter("zelo.chat.quote.edit", "source", "BUDGET", "result", "INSUFFICIENT_STOCK")).isEqualTo(1);
        then(chatMessageService).should(never()).createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void 견적_담당이_예산_후보가_이미_없다고_하면_이미_고른_후보라고_답한다() {
        // given: 견적 담당은 없는 후보를 PRODUCT_NOT_FOUND로 알린다
        given(quoteService.findQuote(10L)).willReturn(Optional.of(overBudgetQuote(15_000L)));
        given(quoteCommandService.applyBudgetOption(1L, 10L, 1L))
                .willThrow(new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        givenAssistantMessage(CANDIDATE_NOT_FOUND);

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseBudgetOption(10L, 1L,
                new ChooseBudgetOptionRequest("quote-1", 1L));

        // then
        assertThat(response.content()).isEqualTo(CANDIDATE_NOT_FOUND);
    }

    @Test
    void 지금_견적에_없는_예산_후보면_견적을_고치지_않고_이미_고른_후보라고_답한다() {
        // given: 계란(2)은 후보가 없는 상품이다
        given(quoteService.findQuote(10L)).willReturn(Optional.of(overBudgetQuote(15_000L)));
        givenAssistantMessage(CANDIDATE_NOT_FOUND);

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseBudgetOption(10L, 1L,
                new ChooseBudgetOptionRequest("quote-1", 2L));

        // then
        assertThat(response.content()).isEqualTo(CANDIDATE_NOT_FOUND);
        assertThat(counter("zelo.chat.quote.edit", "source", "BUDGET", "result", "PRODUCT_NOT_FOUND")).isEqualTo(1);
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 지난_카드의_바꾸기_버튼이면_바꾸지_않고_정해진_문장으로_답한다() {
        // given
        given(quoteService.findQuote(10L)).willReturn(Optional.of(overBudgetQuote(15_000L)));
        givenAssistantMessage(QUOTE_CHANGED);

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseBudgetOption(10L, 1L,
                new ChooseBudgetOptionRequest("quote-9", 1L));

        // then
        assertThat(response.content()).isEqualTo(QUOTE_CHANGED);
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 예산_후보를_고를_때_견적이_만료됐으면_바꾸지_않고_정해진_문장으로_답한다() {
        // given
        given(quoteService.findQuote(10L)).willReturn(Optional.empty());
        givenAssistantMessage(NO_QUOTE_TO_EDIT);

        // when
        ChatMessageResponse response = chatQuoteCommandService.chooseBudgetOption(10L, 1L,
                new ChooseBudgetOptionRequest("quote-1", 1L));

        // then
        assertThat(response.content()).isEqualTo(NO_QUOTE_TO_EDIT);
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 수정_계획은_순서대로_적용하고_마지막_견적을_결과로_준다() {
        // given: "김치 빼고, 계란은 2개로, 햄 200g 추가해 줘"
        Quote current = quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of());
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L), changeQuantity(2L, 2), add("햄", 200, BaseUnit.G)), "");
        QuoteItem twoEggs = new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 2, 9_000L);
        Quote added = quote("quote-4", List.of(twoEggs, HAM_ITEM));
        given(quoteService.findQuote(10L)).willReturn(Optional.of(current));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willReturn(quote("quote-2", List.of(EGG_ITEM)));
        given(quoteCommandService.changeQuantity(1L, 10L, 2L, 2)).willReturn(quote("quote-3", List.of(twoEggs)));
        given(quoteCommandService.addItem(1L, 10L, "햄", 200, BaseUnit.G)).willReturn(added);

        // when
        QuoteEditResult result = chatQuoteCommandService.applyEditPlan(1L, 10L, current, editPlan);

        // then
        InOrder order = inOrder(quoteCommandService);
        order.verify(quoteCommandService).removeItem(1L, 10L, 1L);
        order.verify(quoteCommandService).changeQuantity(1L, 10L, 2L, 2);
        order.verify(quoteCommandService).addItem(1L, 10L, "햄", 200, BaseUnit.G);
        assertThat(result).isEqualTo(new Edited(added, editPlan.edits()));
    }

    @Test
    void 계획하는_사이에_견적이_바뀌었으면_고치지_않는다() {
        // given: 계획은 quote-1을 보고 세웠는데, 적용할 때는 다른 요청이 quote-9로 바꿔 놓았다
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote("quote-9", List.of(KIMCHI_ITEM))));

        // when
        QuoteEditResult result = chatQuoteCommandService.applyEditPlan(1L, 10L,
                quote(List.of(KIMCHI_ITEM), List.of()), QuoteEditPlan.of(List.of(remove(1L)), ""));

        // then
        assertThat(result).isEqualTo(new NotEdited(ErrorCode.QUOTE_NOT_CURRENT));
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 계획하는_사이에_견적이_만료됐으면_고치지_않는다() {
        // given
        given(quoteService.findQuote(10L)).willReturn(Optional.empty());

        // when
        QuoteEditResult result = chatQuoteCommandService.applyEditPlan(1L, 10L,
                quote(List.of(KIMCHI_ITEM), List.of()), QuoteEditPlan.of(List.of(remove(1L)), ""));

        // then
        assertThat(result).isEqualTo(new NotEdited(ErrorCode.QUOTE_EXPIRED));
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 첫_수정이_안내할_수_있는_이유로_실패하면_고치지_못한_결과를_준다() {
        // given
        Quote current = quote(List.of(KIMCHI_ITEM), List.of());
        given(quoteService.findQuote(10L)).willReturn(Optional.of(current));
        given(quoteCommandService.changeQuantity(1L, 10L, 1L, 50))
                .willThrow(new BusinessException(ErrorCode.INSUFFICIENT_STOCK));

        // when
        QuoteEditResult result = chatQuoteCommandService.applyEditPlan(1L, 10L, current,
                QuoteEditPlan.of(List.of(changeQuantity(1L, 50)), ""));

        // then
        assertThat(result).isEqualTo(new NotEdited(ErrorCode.INSUFFICIENT_STOCK));
    }

    @Test
    void 첫_수정이_안내할_수_없는_오류로_실패하면_그대로_던진다() {
        // given
        Quote current = quote(List.of(KIMCHI_ITEM), List.of());
        given(quoteService.findQuote(10L)).willReturn(Optional.of(current));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> chatQuoteCommandService.applyEditPlan(1L, 10L, current,
                QuoteEditPlan.of(List.of(remove(1L)), "")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void 앞의_수정이_저장된_뒤_실패하면_오류_종류와_관계없이_저장된_견적으로_일부_적용_결과를_준다() {
        // given: 김치 빼기는 이미 새 견적(quote-2)으로 저장돼, 오류를 던지면 채팅에는 옛 견적 ID의 카드만 남는다
        Quote current = quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of());
        Quote removed = quote("quote-2", List.of(EGG_ITEM));
        QuoteEdit removeKimchi = remove(1L);
        QuoteEdit threeEggs = changeQuantity(2L, 3);
        given(quoteService.findQuote(10L)).willReturn(Optional.of(current));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willReturn(removed);
        given(quoteCommandService.changeQuantity(1L, 10L, 2L, 3))
                .willThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        // when
        QuoteEditResult result = chatQuoteCommandService.applyEditPlan(1L, 10L, current,
                QuoteEditPlan.of(List.of(removeKimchi, threeEggs), ""));

        // then
        assertThat(result).isEqualTo(
                new PartiallyEdited(removed, List.of(removeKimchi), threeEggs, ErrorCode.SERVICE_UNAVAILABLE));
    }

    private double counter(String name, String... tags) {
        return meterRegistry.get(name).tags(tags).counter().count();
    }

    private void givenAssistantMessage(String content) {
        given(chatMessageService.createAssistantMessage(10L, 1L, content))
                .willReturn(ChatMessage.of(10L, ChatSender.ASSISTANT, content));
    }

    private static ChatMessage cardMessage(String content) {
        return ChatMessage.of(10L, ChatSender.ASSISTANT, content, "{\"type\":\"QUOTE_CARD\"}");
    }

    private static QuoteEdit remove(Long productId) {
        return QuoteEdit.of(QuoteEditAction.REMOVE, productId, 0, "", 0, BaseUnit.EA);
    }

    private static QuoteEdit changeQuantity(Long productId, Integer quantity) {
        return QuoteEdit.of(QuoteEditAction.CHANGE_QUANTITY, productId, quantity, "", 0, BaseUnit.EA);
    }

    private static QuoteEdit add(String ingredientName, Integer amount, BaseUnit unit) {
        return QuoteEdit.of(QuoteEditAction.ADD, 0L, 0, ingredientName, amount, unit);
    }

    private static Quote quoteWithSubstitutes(String quoteId, List<QuoteItem> items) {
        long total = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        return new Quote(quoteId, 1L, 10L, "김치볶음밥", items, List.of(), total, now, now.plusMinutes(10),
                Set.of(), List.of(LEEK_SUBSTITUTE));
    }

    // 백김치(12,000원)·계란(4,500원)을 담은 16,500원 견적. 백김치에만 더 싼 후보가 있다
    private static Quote overBudgetQuote(Long budget) {
        return budgetQuote("quote-1", List.of(KIMCHI_ITEM, EGG_ITEM), budget, List.of(KIMCHI_BUDGET_OPTION));
    }

    private static Quote budgetQuote(String quoteId, List<QuoteItem> items, Long budget, List<BudgetOption> budgetOptions) {
        long total = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        return new Quote(quoteId, 1L, 10L, "김치볶음밥", items, List.of(), total, now, now.plusMinutes(10),
                Set.of(), List.of(), budget, budgetOptions);
    }

    private static Quote quote(List<QuoteItem> items, List<ExcludedItem> excludedItems) {
        return quote("quote-1", items, excludedItems);
    }

    // 견적 담당 수정 메서드는 고칠 때마다 새 견적 ID로 저장한다
    private static Quote quote(String quoteId, List<QuoteItem> items) {
        return quote(quoteId, items, List.of());
    }

    private static Quote quote(String quoteId, List<QuoteItem> items, List<ExcludedItem> excludedItems) {
        long total = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        return new Quote(quoteId, 1L, 10L, "김치볶음밥", items, excludedItems, total, now, now.plusMinutes(10));
    }
}
