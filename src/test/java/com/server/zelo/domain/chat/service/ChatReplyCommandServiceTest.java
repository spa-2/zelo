package com.server.zelo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import com.server.zelo.ai.gateway.LlmGateway;
import com.server.zelo.ai.gateway.LlmReply;
import com.server.zelo.ai.gateway.LlmResult;
import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.ai.plan.QuoteEditAction;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.ai.prompt.ChatPrompt;
import com.server.zelo.ai.prompt.MenuPlanPrompt;
import com.server.zelo.ai.prompt.QuoteEditPrompt;
import com.server.zelo.ai.prompt.QuoteExplainPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.dto.response.QuoteCardStatus;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.model.QuoteEditResult.Edited;
import com.server.zelo.domain.chat.model.QuoteEditResult.NotEdited;
import com.server.zelo.domain.chat.model.QuoteEditResult.PartiallyEdited;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.model.SubstituteItem;
import com.server.zelo.domain.quote.service.QuoteService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

// 메시지 하나에 LLM으로 답하는 흐름. 견적을 바꾸는 구간(락)은 ChatQuoteFacade를 mock으로 두고, 그 결과에 따른 답만 본다
@ExtendWith(MockitoExtension.class)
class ChatReplyCommandServiceTest {

    private static final Map<String, BaseUnit> INGREDIENT_UNITS = Map.of("김치", BaseUnit.G, "계란", BaseUnit.EA);
    private static final QuoteItem KIMCHI_ITEM = new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 1, 12_000L);
    private static final QuoteItem EGG_ITEM = new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 1, 4_500L);
    private static final QuoteItem LEEK_ITEM = new QuoteItem(5L, "VEG-0102", "수입 대파", "1단", 2_500L, 1, 2_500L);
    private static final SubstituteItem LEEK_SUBSTITUTE = new SubstituteItem("대파", "국산 대파", LEEK_ITEM);
    private static final String NO_QUOTE_TO_EDIT = "고칠 견적이 없어요. 견적이 만료됐을 수 있으니 만들 메뉴를 다시 말씀해 주세요.";
    private static final String ASK_EDIT_AGAIN = "어떤 상품을 어떻게 바꿀지 다시 말씀해 주세요.";
    private static final String NOT_IN_QUOTE = "견적 카드에 없는 상품이라 고치지 못했어요. 카드에 있는 상품 이름으로 다시 말씀해 주세요.";
    private static final String NOT_ENOUGH_STOCK = "재고가 부족해서 그 수량으로 바꿀 수 없어요. 수량을 줄여서 다시 말씀해 주세요.";
    private static final String QUOTE_CHANGED = "견적이 방금 바뀌어서 고치지 않았어요. 최신 견적 카드를 확인하고 다시 말씀해 주세요.";
    private static final String QUOTE_IN_PROGRESS = "이 채팅방의 견적을 처리하고 있는 다른 요청이 있어요. 잠시 후 다시 말씀해 주세요.";

    @Mock
    private LlmGateway llmGateway;

    @Mock
    private QuoteService quoteService;

    @Mock
    private ProductService productService;

    @Mock
    private ChatQuoteFacade chatQuoteFacade;

    @Mock
    private ChatMessageService chatMessageService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);

    // 실제 프롬프트 파일과 견적 글이 LLM으로 가는지 보려고 진짜 객체를 쓴다
    @Spy
    private ChatPrompt chatPrompt = new ChatPrompt(new ClassPathResource("prompts/chat.md"));

    @Spy
    private MenuPlanPrompt menuPlanPrompt = new MenuPlanPrompt(new ClassPathResource("prompts/menu-plan.md"));

    @Spy
    private QuoteEditPrompt quoteEditPrompt = new QuoteEditPrompt(new ClassPathResource("prompts/quote-edit.md"));

    @Spy
    private QuoteExplainPrompt quoteExplainPrompt = new QuoteExplainPrompt(new ClassPathResource("prompts/quote-explain.md"));

    @InjectMocks
    private ChatReplyCommandService chatReplyCommandService;

    // 메뉴 계획 판단을 일반 질문으로 깔아 두고, 견적 흐름 테스트에서만 바꾼다
    @BeforeEach
    void setUp() {
        lenient().when(productService.findIngredientUnits()).thenReturn(INGREDIENT_UNITS);
        lenient().when(llmGateway.planMenu(anyString(), anyList(), anyString(), anyMap()))
                .thenReturn(planResult(plan(MenuPlanIntent.GENERAL_QUESTION)));
    }

    @Test
    void 일반_질문은_채팅_프롬프트와_대화_기록으로_LLM에_묻고_답을_AI_메시지로_저장한다() {
        // given
        List<ChatMessage> history = List.of(
                ChatMessage.of(10L, ChatSender.USER, "초등학생 둘이랑 셋이 김치볶음밥"),
                ChatMessage.of(10L, ChatSender.ASSISTANT, "김치, 밥, 계란이 필요해요."));
        given(llmGateway.explain(chatPrompt.systemPrompt(), history, "그거 맵지 않게 해 줘"))
                .willReturn(LlmReply.of("백김치로 준비할게요.", "gpt-test", 10, 5));
        givenAssistantMessage("백김치로 준비할게요.");

        // when
        ChatMessageResponse response = reply(history, "그거 맵지 않게 해 줘");

        // then
        InOrder order = inOrder(llmGateway, chatMessageService);
        order.verify(llmGateway).explain(chatPrompt.systemPrompt(), history, "그거 맵지 않게 해 줘");
        order.verify(chatMessageService).createAssistantMessage(10L, 1L, "백김치로 준비할게요.");
        assertThat(response.sender()).isEqualTo(ChatSender.ASSISTANT);
        assertThat(response.content()).isEqualTo("백김치로 준비할게요.");
        assertThat(response.quoteCardStatus()).isNull();
        assertThat(chatPrompt.systemPrompt()).contains("[주문하기] 버튼");
        assertThat(meterRegistry.get("zelo.chat.reply").tag("intent", "GENERAL_QUESTION").tag("outcome", "SUCCESS")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void 응답_모양은_부르는_쪽이_넘긴_변환으로_만든다() {
        // given
        givenAssistantAnswer();

        // when: 새 대화는 방 ID를 함께 담은 모양으로 바꾼다
        String response = chatReplyCommandService.replyTo(10L, 1L, List.of(), "안녕",
                message -> "room-10:" + message.content());

        // then
        assertThat(response).isEqualTo("room-10:답변");
    }

    @Test
    void LLM을_쓸_수_없으면_고정_문구를_저장하고_그_실패_코드와_고정_문구_메시지를_담아_예외를_던진다() {
        // given
        given(llmGateway.explain(anyString(), anyList(), anyString()))
                .willThrow(new BusinessException(ErrorCode.AI_TIMEOUT));
        given(chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_TIMEOUT))
                .willReturn(fallbackMessage("AI_002"));
        given(chatMessageService.isRetryable("{\"type\":\"FALLBACK\",\"code\":\"AI_002\"}")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> reply("안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT);
                    assertThat(exception.getData()).isInstanceOfSatisfying(ChatMessageResponse.class, data -> {
                        assertThat(data.sender()).isEqualTo(ChatSender.ASSISTANT);
                        assertThat(data.payload()).isEqualTo("{\"type\":\"FALLBACK\",\"code\":\"AI_002\"}");
                        assertThat(data.retryable()).isTrue();
                    });
                });
        then(chatMessageService).should(never()).createAssistantMessage(anyLong(), anyLong(), anyString());
        assertThat(counter("zelo.chat.fallback", "code", "AI_002")).isEqualTo(1);
        assertThat(meterRegistry.get("zelo.chat.reply").tag("intent", "GENERAL_QUESTION").tag("outcome", "AI_002")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void 다시_할_수_없는_오류의_고정_문구는_retryable이_false다() {
        // given
        String payload = "{\"type\":\"FALLBACK\",\"code\":\"AI_009\"}";
        given(llmGateway.explain(anyString(), anyList(), anyString()))
                .willThrow(new BusinessException(ErrorCode.AI_INVALID_RESPONSE));
        given(chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_INVALID_RESPONSE))
                .willReturn(fallbackMessage("AI_009"));
        given(chatMessageService.isRetryable(payload)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> reply("안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(((ChatMessageResponse) exception.getData()).retryable()).isFalse());
    }

    @Test
    void LLM_답을_저장하다_실패하면_고정_문구로_바꾸지_않고_그_오류를_그대로_던진다() {
        // given: LLM은 성공했지만 답을 저장하기 직전에 방 권한이 없어진 경우
        given(llmGateway.explain(anyString(), anyList(), anyString())).willReturn(LlmReply.of("답변", "gpt-test", 10, 5));
        given(chatMessageService.createAssistantMessage(10L, 1L, "답변"))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> reply("안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
                    assertThat(exception.getData()).isNull();
                });
        then(chatMessageService).should(never()).createFallbackMessage(anyLong(), anyLong(), any());
    }

    @Test
    void 메뉴_계획에는_재료_테이블_목록과_대화_기록과_이번_말을_보낸다() {
        // given
        List<ChatMessage> history = List.of(ChatMessage.of(10L, ChatSender.USER, "초등학생 둘이랑 셋이 김치볶음밥"));
        givenAssistantAnswer();
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);

        // when
        reply(history, "4인분으로 바꿔줘");

        // then: 이 방에는 견적이 없어 현재 견적 메뉴는 "없음"
        then(llmGateway).should().planMenu(systemPrompt.capture(), eq(history), eq("4인분으로 바꿔줘"), eq(INGREDIENT_UNITS));
        assertThat(systemPrompt.getValue()).contains("- 김치: G").contains("## 현재 견적 메뉴\n없음");
    }

    @Test
    void 메뉴_계획이_실패하면_고정_문구로_응답하고_답변이나_견적을_만들지_않는다() {
        // given
        given(llmGateway.planMenu(anyString(), anyList(), anyString(), anyMap()))
                .willThrow(new BusinessException(ErrorCode.AI_INVALID_RESPONSE));
        given(chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_INVALID_RESPONSE))
                .willReturn(fallbackMessage("AI_009"));

        // when & then
        assertThatThrownBy(() -> reply("안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE);
                    assertThat(exception.getData()).isInstanceOf(ChatMessageResponse.class);
                });
        then(llmGateway).should(never()).explain(any(), any(), any());
        then(chatQuoteFacade).shouldHaveNoInteractions();
        assertThat(meterRegistry.get("zelo.chat.reply").tag("intent", "UNKNOWN").tag("outcome", "AI_009")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void 현재_견적이_있으면_그_메뉴_이름을_메뉴_계획에_넣는다() {
        // given
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote(List.of(KIMCHI_ITEM), List.of())));
        givenAssistantAnswer();
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);

        // when
        reply("4인분으로 바꿔줘");

        // then
        then(llmGateway).should().planMenu(systemPrompt.capture(), anyList(), anyString(), anyMap());
        assertThat(systemPrompt.getValue()).contains("## 현재 견적 메뉴\n김치볶음밥\n");
    }

    @Test
    void 상품도_후보도_없는_견적은_견적이_없는_것으로_본다() {
        // given: 견적 담당은 상품 0개 견적도 저장한다(예전 카드로 주문되지 않게 덮어쓰려고)
        given(quoteService.findQuote(10L))
                .willReturn(Optional.of(quote(List.of(), List.of(new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED")))));
        givenAssistantAnswer();
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);

        // when
        reply("계란 빼줘");

        // then
        then(llmGateway).should().planMenu(systemPrompt.capture(), anyList(), anyString(), anyMap());
        assertThat(systemPrompt.getValue()).contains("## 현재 견적 메뉴\n없음\n");
    }

    @Test
    void 장보기_요청이면_견적을_발급하고_안내_문장과_견적_카드를_함께_저장한다() {
        // given
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        Quote quote = quote(List.of(KIMCHI_ITEM), List.of());
        givenPlan(menuPlan);
        given(chatQuoteFacade.createQuote(1L, 10L, menuPlan)).willReturn(quote);
        given(llmGateway.explain(quoteExplainPrompt.systemPrompt(), List.of(), quoteExplainPrompt.userText(menuPlan, quote)))
                .willReturn(LlmReply.of("맵지 않은 김치로 골랐어요. 주문하려면 [주문하기]를 눌러 주세요.", "gpt-test", 1, 1));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "맵지 않은 김치로 골랐어요. 주문하려면 [주문하기]를 눌러 주세요.",
                QuoteCardPayload.from(quote)))
                .willReturn(cardMessage("맵지 않은 김치로 골랐어요. 주문하려면 [주문하기]를 눌러 주세요."));

        // when
        ChatMessageResponse response = reply("김치볶음밥 재료 준비해 줘");

        // then: 일반 채팅 프롬프트로는 답하지 않고, 방금 만든 카드는 현재 견적이라 ACTIVE다
        assertThat(response.payload()).contains("QUOTE_CARD");
        assertThat(response.quoteCardStatus()).isEqualTo(QuoteCardStatus.ACTIVE);
        then(llmGateway).should(never()).explain(eq(chatPrompt.systemPrompt()), anyList(), anyString());
        then(chatMessageService).should(never()).findQuoteCardId(any());
    }

    @Test
    void 견적_발급은_메뉴_계획_뒤에_Facade의_락으로_하고_안내_문장은_그_뒤에_만든다() {
        // given
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        Quote quote = quote(List.of(KIMCHI_ITEM), List.of());
        givenPlan(menuPlan);
        given(chatQuoteFacade.createQuote(1L, 10L, menuPlan)).willReturn(quote);
        given(llmGateway.explain(eq(quoteExplainPrompt.systemPrompt()), anyList(), anyString()))
                .willReturn(LlmReply.of("김치를 골랐어요.", "gpt-test", 1, 1));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "김치를 골랐어요.", QuoteCardPayload.from(quote)))
                .willReturn(cardMessage("김치를 골랐어요."));

        // when
        reply("김치볶음밥 재료 준비해 줘");

        // then
        InOrder order = inOrder(llmGateway, chatQuoteFacade);
        order.verify(llmGateway).planMenu(anyString(), anyList(), anyString(), anyMap());
        order.verify(chatQuoteFacade).createQuote(1L, 10L, menuPlan);
        order.verify(llmGateway).explain(eq(quoteExplainPrompt.systemPrompt()), anyList(), anyString());
    }

    @Test
    void 같은_방의_다른_요청이_락을_잡고_있으면_견적을_발급하지_않고_잠시_후_다시_말하라고_답한다() {
        // given
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        givenPlan(menuPlan);
        given(chatQuoteFacade.createQuote(1L, 10L, menuPlan)).willThrow(new BusinessException(ErrorCode.TIMEOUT_EXCEPTION));
        givenAssistantMessage(QUOTE_IN_PROGRESS);

        // when
        ChatMessageResponse response = reply("김치볶음밥 재료 준비해 줘");

        // then
        assertThat(response.content()).isEqualTo(QUOTE_IN_PROGRESS);
        assertThat(counter("zelo.chat.quote.busy")).isEqualTo(1);
        then(chatMessageService).should(never()).createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
        then(llmGateway).should(never()).explain(any(), any(), any());
    }

    @Test
    void 견적_발급이_실패하면_고정_문구로_바꾸지_않고_그_오류를_그대로_던진다() {
        // given: LLM과 상관없는 서버 오류
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        givenPlan(menuPlan);
        given(chatQuoteFacade.createQuote(1L, 10L, menuPlan)).willThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> reply("김치볶음밥 재료 준비해 줘"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(exception.getData()).isNull();
                });
        then(chatMessageService).should(never()).createFallbackMessage(anyLong(), anyLong(), any());
        then(llmGateway).should(never()).explain(any(), any(), any());
        assertThat(counter("zelo.chat.quote.busy")).isZero();
    }

    @Test
    void 견적_안내_문장을_받지_못해도_카드는_정해진_문장으로_저장하고_성공으로_응답한다() {
        // given
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        Quote quote = quote(List.of(KIMCHI_ITEM), List.of());
        givenPlan(menuPlan);
        given(chatQuoteFacade.createQuote(1L, 10L, menuPlan)).willReturn(quote);
        given(llmGateway.explain(anyString(), anyList(), anyString())).willThrow(new BusinessException(ErrorCode.AI_TIMEOUT));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "견적을 준비했어요. 내용을 확인해 주세요.",
                QuoteCardPayload.from(quote)))
                .willReturn(cardMessage("견적을 준비했어요. 내용을 확인해 주세요."));

        // when
        ChatMessageResponse response = reply("김치볶음밥 재료 준비해 줘");

        // then
        assertThat(response.content()).isEqualTo("견적을 준비했어요. 내용을 확인해 주세요.");
        then(chatMessageService).should(never()).createFallbackMessage(anyLong(), anyLong(), any());
        assertThat(counter("zelo.chat.explain.degraded", "code", "AI_002")).isEqualTo(1);
    }

    @Test
    void 담은_상품이_없으면_카드_없이_빠진_이유만_안내한다() {
        // given: 주문할 상품이 없는데 [주문하기] 버튼이 뜨면 안 된다
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        Quote quote = quote(List.of(), List.of(new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED")));
        givenPlan(menuPlan);
        given(chatQuoteFacade.createQuote(1L, 10L, menuPlan)).willReturn(quote);
        given(llmGateway.explain(quoteExplainPrompt.systemPrompt(), List.of(), quoteExplainPrompt.userText(menuPlan, quote)))
                .willReturn(LlmReply.of("소주는 AI가 대신 살 수 없어요.", "gpt-test", 1, 1));
        givenAssistantMessage("소주는 AI가 대신 살 수 없어요.");

        // when
        ChatMessageResponse response = reply("김치볶음밥 재료 준비해 줘");

        // then
        assertThat(response.payload()).isNull();
        assertThat(response.quoteCardStatus()).isNull();
        then(chatMessageService).should(never()).createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void 담은_상품이_없을_때_안내도_받지_못하면_고정_문구로_응답한다() {
        // given
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        givenPlan(menuPlan);
        given(chatQuoteFacade.createQuote(1L, 10L, menuPlan))
                .willReturn(quote(List.of(), List.of(new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED"))));
        given(llmGateway.explain(anyString(), anyList(), anyString())).willThrow(new BusinessException(ErrorCode.AI_TIMEOUT));
        given(chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_TIMEOUT)).willReturn(fallbackMessage("AI_002"));

        // when & then
        assertThatThrownBy(() -> reply("김치볶음밥 재료 준비해 줘"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT));
    }

    @Test
    void 장보기_견적에_담은_상품이_없어도_후보가_있으면_고를_수_있게_카드를_저장한다() {
        // given
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        Quote onlySubstitute = quoteWithSubstitutes("quote-1", List.of());
        givenPlan(menuPlan);
        given(chatQuoteFacade.createQuote(1L, 10L, menuPlan)).willReturn(onlySubstitute);
        given(llmGateway.explain(eq(quoteExplainPrompt.systemPrompt()), anyList(), anyString()))
                .willReturn(LlmReply.of("국산 대파가 품절이라 수입 대파를 후보로 찾았어요.", "gpt-test", 1, 1));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "국산 대파가 품절이라 수입 대파를 후보로 찾았어요.",
                QuoteCardPayload.from(onlySubstitute)))
                .willReturn(cardMessage("국산 대파가 품절이라 수입 대파를 후보로 찾았어요."));

        // when
        ChatMessageResponse response = reply("대파 사 줘");

        // then
        assertThat(response.content()).isEqualTo("국산 대파가 품절이라 수입 대파를 후보로 찾았어요.");
        assertThat(response.quoteCardStatus()).isEqualTo(QuoteCardStatus.ACTIVE);
    }

    @Test
    void 견적_수정_판단이어도_고칠_견적이_없으면_LLM과_견적_수정_없이_정해진_문장으로_답한다() {
        // given: LLM 답을 믿지 않는다. 견적이 만료됐을 수도 있다
        givenPlan(plan(MenuPlanIntent.QUOTE_EDIT));
        givenAssistantMessage(NO_QUOTE_TO_EDIT);

        // when
        ChatMessageResponse response = reply("계란 빼줘");

        // then
        assertThat(response.content()).isEqualTo(NO_QUOTE_TO_EDIT);
        then(llmGateway).should(never()).planQuoteEdit(any(), any(), any(), any());
        then(chatQuoteFacade).shouldHaveNoInteractions();
        assertThat(counter("zelo.chat.quote.edit", "source", "CHAT", "result", "QUOTE_EXPIRED")).isEqualTo(1);
    }

    @Test
    void 수정_계획에는_현재_견적_목록과_재료_목록과_대화_기록과_이번_말을_보낸다() {
        // given
        List<ChatMessage> history = List.of(ChatMessage.of(10L, ChatSender.USER, "김치볶음밥 재료 준비해 줘"));
        Quote current = quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of());
        givenEditRequest(current, QuoteEditPlan.of(List.of(), "어떤 상품을 뺄까요?"));
        givenAssistantMessage("어떤 상품을 뺄까요?");
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);

        // when
        reply(history, "그거 빼줘");

        // then
        then(llmGateway).should().planQuoteEdit(systemPrompt.capture(), eq(history), eq("그거 빼줘"), eq(INGREDIENT_UNITS));
        assertThat(systemPrompt.getValue())
                .contains("메뉴: 김치볶음밥")
                .contains("- productId 1: 백김치 (판매 단위 1kg) 1개")
                .contains("- productId 2: 계란 (판매 단위 10구) 1개")
                .contains("- 김치: G");
    }

    @Test
    void 무엇을_고칠지_정하지_못하면_견적을_고치지_않고_LLM이_되물은_문장으로_답한다() {
        // given
        givenEditRequest(quote(List.of(KIMCHI_ITEM), List.of()),
                QuoteEditPlan.of(List.of(), "포기김치와 백김치 중 어떤 김치를 뺄까요?"));
        givenAssistantMessage("포기김치와 백김치 중 어떤 김치를 뺄까요?");

        // when
        ChatMessageResponse response = reply("김치 빼줘");

        // then
        assertThat(response.content()).isEqualTo("포기김치와 백김치 중 어떤 김치를 뺄까요?");
        then(chatQuoteFacade).shouldHaveNoInteractions();
        assertThat(counter("zelo.chat.quote.edit", "source", "CHAT", "result", "ASK_AGAIN")).isEqualTo(1);
    }

    @Test
    void 수정도_되묻는_문장도_없으면_정해진_문장으로_다시_물어본다() {
        // given
        givenEditRequest(quote(List.of(KIMCHI_ITEM), List.of()), QuoteEditPlan.of(List.of(), " "));
        givenAssistantMessage(ASK_EDIT_AGAIN);

        // when
        ChatMessageResponse response = reply("바꿔줘");

        // then
        assertThat(response.content()).isEqualTo(ASK_EDIT_AGAIN);
        then(chatQuoteFacade).shouldHaveNoInteractions();
    }

    @Test
    void 하나라도_현재_견적에_없는_상품을_가리키면_락을_걸지도_고치지도_않는다() {
        // given: 계란 추가는 할 수 있지만 99번 상품이 견적에 없어 계란도 더하지 않는다
        givenEditRequest(quote(List.of(KIMCHI_ITEM), List.of()),
                QuoteEditPlan.of(List.of(add("계란", 2, BaseUnit.EA), remove(99L)), ""));
        givenAssistantMessage(NOT_IN_QUOTE);

        // when
        ChatMessageResponse response = reply("계란 2개 더하고 쌀 빼줘");

        // then
        assertThat(response.content()).isEqualTo(NOT_IN_QUOTE);
        then(chatQuoteFacade).shouldHaveNoInteractions();
        assertThat(counter("zelo.chat.quote.edit", "source", "CHAT", "result", "NOT_IN_QUOTE")).isEqualTo(1);
    }

    @Test
    void 후보만_있는_견적도_고칠_견적으로_본다() {
        // given: 담은 상품 없이 후보만 있는 견적에 "김치 빼줘" — 견적이 없다고 하지 않고 카드에 없는 상품이라고 답한다
        givenEditRequest(quoteWithSubstitutes("quote-1", List.of()), QuoteEditPlan.of(List.of(remove(1L)), ""));
        givenAssistantMessage(NOT_IN_QUOTE);

        // when
        ChatMessageResponse response = reply("김치 빼줘");

        // then
        assertThat(response.content()).isEqualTo(NOT_IN_QUOTE);
    }

    @Test
    void 견적_수정은_LLM_계획_뒤에_Facade의_락으로_적용하고_안내_문장은_그_뒤에_만든다() {
        // given
        Quote current = quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of());
        Quote removed = quote("quote-2", List.of(EGG_ITEM));
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L)), "");
        givenEditRequest(current, editPlan);
        given(chatQuoteFacade.applyEditPlan(1L, 10L, current, editPlan)).willReturn(new Edited(removed, editPlan.edits()));
        given(llmGateway.explain(eq(quoteExplainPrompt.systemPrompt()), anyList(), anyString()))
                .willReturn(LlmReply.of("김치를 뺐어요.", "gpt-test", 1, 1));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "김치를 뺐어요.", QuoteCardPayload.from(removed)))
                .willReturn(cardMessage("김치를 뺐어요."));

        // when
        ChatMessageResponse response = reply("김치 빼줘");

        // then
        InOrder order = inOrder(llmGateway, chatQuoteFacade, chatMessageService);
        order.verify(llmGateway).planQuoteEdit(anyString(), anyList(), anyString(), anyMap());
        order.verify(chatQuoteFacade).applyEditPlan(1L, 10L, current, editPlan);
        order.verify(llmGateway).explain(eq(quoteExplainPrompt.systemPrompt()), anyList(), anyString());
        order.verify(chatMessageService).createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
        assertThat(response.quoteCardStatus()).isEqualTo(QuoteCardStatus.ACTIVE);
        assertThat(counter("zelo.chat.quote.edit", "source", "CHAT", "result", "APPLIED")).isEqualTo(1);
    }

    @Test
    void 모두_고쳤으면_수정한_내용으로_안내하고_마지막_견적으로_카드를_만든다() {
        // given: "김치 빼고 계란은 2개로"
        Quote current = quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of());
        QuoteItem twoEggs = new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 2, 9_000L);
        Quote edited = quote("quote-3", List.of(twoEggs));
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L), changeQuantity(2L, 2)), "");
        givenEditRequest(current, editPlan);
        given(chatQuoteFacade.applyEditPlan(1L, 10L, current, editPlan)).willReturn(new Edited(edited, editPlan.edits()));
        String editedText = quoteExplainPrompt.editedText(current, edited, editPlan.edits(), null, null);
        given(llmGateway.explain(quoteExplainPrompt.systemPrompt(), List.of(), editedText))
                .willReturn(LlmReply.of("김치를 빼고 계란을 2개로 바꿨어요.", "gpt-test", 1, 1));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "김치를 빼고 계란을 2개로 바꿨어요.", QuoteCardPayload.from(edited)))
                .willReturn(cardMessage("김치를 빼고 계란을 2개로 바꿨어요."));

        // when
        ChatMessageResponse response = reply("김치 빼고 계란은 2개로");

        // then
        assertThat(response.payload()).contains("QUOTE_CARD");
        assertThat(editedText)
                .contains("수정한 내용:\n- 백김치 (1kg) 빼기\n- 계란 (10구) 2개로 바꾸기")
                .doesNotContain("하지 못한 수정");
    }

    @Test
    void 앞의_수정만_적용됐으면_저장된_견적으로_카드를_만들고_하지_못한_수정을_안내한다() {
        // given: 김치는 이미 빠져 새 견적으로 저장됐으므로 그 견적을 보여 줘야 한다
        Quote current = quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of());
        Quote removed = quote("quote-2", List.of(EGG_ITEM));
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L), changeQuantity(2L, 50)), "");
        givenEditRequest(current, editPlan);
        given(chatQuoteFacade.applyEditPlan(1L, 10L, current, editPlan)).willReturn(new PartiallyEdited(
                removed, List.of(editPlan.edits().getFirst()), editPlan.edits().get(1), ErrorCode.INSUFFICIENT_STOCK));
        given(llmGateway.explain(eq(quoteExplainPrompt.systemPrompt()), eq(List.of()), anyString()))
                .willReturn(LlmReply.of("김치는 뺐지만 계란은 재고가 부족해요.", "gpt-test", 1, 1));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "김치는 뺐지만 계란은 재고가 부족해요.",
                QuoteCardPayload.from(removed)))
                .willReturn(cardMessage("김치는 뺐지만 계란은 재고가 부족해요."));
        ArgumentCaptor<String> editedText = ArgumentCaptor.forClass(String.class);

        // when
        ChatMessageResponse response = reply("김치 빼고 계란 50개로");

        // then
        assertThat(response.payload()).contains("QUOTE_CARD");
        then(llmGateway).should().explain(anyString(), anyList(), editedText.capture());
        assertThat(editedText.getValue())
                .contains("수정한 내용:\n- 백김치 (1kg) 빼기")
                .contains("하지 못한 수정:\n- 계란 (10구) 50개로 바꾸기: 재고 부족");
        assertThat(counter("zelo.chat.quote.edit", "source", "CHAT", "result", "PARTIAL")).isEqualTo(1);
    }

    @Test
    void 앞의_수정만_적용되고_안내할_수_없는_오류로_멈췄으면_오류_문구를_이유로_안내한다() {
        // given
        Quote current = quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of());
        Quote removed = quote("quote-2", List.of(EGG_ITEM));
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L), changeQuantity(2L, 3)), "");
        givenEditRequest(current, editPlan);
        given(chatQuoteFacade.applyEditPlan(1L, 10L, current, editPlan)).willReturn(new PartiallyEdited(
                removed, List.of(editPlan.edits().getFirst()), editPlan.edits().get(1), ErrorCode.SERVICE_UNAVAILABLE));
        given(llmGateway.explain(eq(quoteExplainPrompt.systemPrompt()), eq(List.of()), anyString()))
                .willReturn(LlmReply.of("김치는 뺐지만 계란 수량은 바꾸지 못했어요.", "gpt-test", 1, 1));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "김치는 뺐지만 계란 수량은 바꾸지 못했어요.",
                QuoteCardPayload.from(removed)))
                .willReturn(cardMessage("김치는 뺐지만 계란 수량은 바꾸지 못했어요."));
        ArgumentCaptor<String> editedText = ArgumentCaptor.forClass(String.class);

        // when
        ChatMessageResponse response = reply("김치 빼고 계란 3개로");

        // then
        assertThat(response.payload()).contains("QUOTE_CARD");
        then(llmGateway).should().explain(anyString(), anyList(), editedText.capture());
        assertThat(editedText.getValue())
                .contains("하지 못한 수정:\n- 계란 (10구) 3개로 바꾸기: " + ErrorCode.SERVICE_UNAVAILABLE.getMessage());
        then(chatMessageService).should(never()).createFallbackMessage(anyLong(), anyLong(), any());
    }

    @Test
    void 계획하는_사이에_견적이_바뀌어_고치지_못했으면_정해진_문장으로_답한다() {
        // given
        Quote current = quote(List.of(KIMCHI_ITEM), List.of());
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L)), "");
        givenEditRequest(current, editPlan);
        given(chatQuoteFacade.applyEditPlan(1L, 10L, current, editPlan)).willReturn(new NotEdited(ErrorCode.QUOTE_NOT_CURRENT));
        givenAssistantMessage(QUOTE_CHANGED);

        // when
        ChatMessageResponse response = reply("김치 빼줘");

        // then
        assertThat(response.content()).isEqualTo(QUOTE_CHANGED);
        assertThat(counter("zelo.chat.quote.edit", "source", "CHAT", "result", "QUOTE_NOT_CURRENT")).isEqualTo(1);
        then(chatMessageService).should(never()).createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
        then(llmGateway).should(never()).explain(any(), any(), any());
    }

    @Test
    void 계획하는_사이에_견적이_만료돼_고치지_못했으면_정해진_문장으로_답한다() {
        // given
        Quote current = quote(List.of(KIMCHI_ITEM), List.of());
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L)), "");
        givenEditRequest(current, editPlan);
        given(chatQuoteFacade.applyEditPlan(1L, 10L, current, editPlan)).willReturn(new NotEdited(ErrorCode.QUOTE_EXPIRED));
        givenAssistantMessage(NO_QUOTE_TO_EDIT);

        // when
        ChatMessageResponse response = reply("김치 빼줘");

        // then
        assertThat(response.content()).isEqualTo(NO_QUOTE_TO_EDIT);
    }

    @Test
    void 첫_수정에서_재고가_부족해_고치지_못했으면_정해진_문장으로_답한다() {
        // given
        Quote current = quote(List.of(KIMCHI_ITEM), List.of());
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(changeQuantity(1L, 50)), "");
        givenEditRequest(current, editPlan);
        given(chatQuoteFacade.applyEditPlan(1L, 10L, current, editPlan)).willReturn(new NotEdited(ErrorCode.INSUFFICIENT_STOCK));
        givenAssistantMessage(NOT_ENOUGH_STOCK);

        // when
        ChatMessageResponse response = reply("김치 50개로");

        // then
        assertThat(response.content()).isEqualTo(NOT_ENOUGH_STOCK);
        assertThat(counter("zelo.chat.quote.edit", "source", "CHAT", "result", "INSUFFICIENT_STOCK")).isEqualTo(1);
    }

    @Test
    void 같은_방의_다른_수정이_락을_잡고_있으면_고치지_않고_잠시_후_다시_말하라고_답한다() {
        // given
        Quote current = quote(List.of(KIMCHI_ITEM), List.of());
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L)), "");
        givenEditRequest(current, editPlan);
        given(chatQuoteFacade.applyEditPlan(1L, 10L, current, editPlan))
                .willThrow(new BusinessException(ErrorCode.TIMEOUT_EXCEPTION));
        givenAssistantMessage(QUOTE_IN_PROGRESS);

        // when
        ChatMessageResponse response = reply("김치 빼줘");

        // then
        assertThat(response.content()).isEqualTo(QUOTE_IN_PROGRESS);
        assertThat(counter("zelo.chat.quote.busy")).isEqualTo(1);
        assertThat(counter("zelo.chat.quote.edit", "source", "CHAT", "result", "BUSY")).isEqualTo(1);
    }

    @Test
    void 모든_상품을_빼면_카드_없이_안내만_한다() {
        // given
        Quote current = quote(List.of(KIMCHI_ITEM), List.of());
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L)), "");
        givenEditRequest(current, editPlan);
        given(chatQuoteFacade.applyEditPlan(1L, 10L, current, editPlan))
                .willReturn(new Edited(quote("quote-2", List.of()), editPlan.edits()));
        given(llmGateway.explain(eq(quoteExplainPrompt.systemPrompt()), eq(List.of()), anyString()))
                .willReturn(LlmReply.of("김치를 빼서 주문할 상품이 없어요.", "gpt-test", 1, 1));
        givenAssistantMessage("김치를 빼서 주문할 상품이 없어요.");

        // when
        ChatMessageResponse response = reply("김치 빼줘");

        // then
        assertThat(response.payload()).isNull();
        then(chatMessageService).should(never()).createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void 수정_계획_LLM이_실패하면_고정_문구로_응답하고_견적을_고치지_않는다() {
        // given
        givenEditRequest(quote(List.of(KIMCHI_ITEM), List.of()), QuoteEditPlan.of(List.of(), ""));
        given(llmGateway.planQuoteEdit(anyString(), anyList(), anyString(), anyMap()))
                .willThrow(new BusinessException(ErrorCode.AI_TIMEOUT));
        given(chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_TIMEOUT)).willReturn(fallbackMessage("AI_002"));

        // when & then
        assertThatThrownBy(() -> reply("김치 빼줘"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT);
                    assertThat(exception.getData()).isInstanceOf(ChatMessageResponse.class);
                });
        then(chatQuoteFacade).shouldHaveNoInteractions();
    }

    @Test
    void 견적_수정_중_안내할_수_없는_오류는_고정_문구로_바꾸지_않고_그대로_던진다() {
        // given: LLM과 상관없는 서버 오류
        Quote current = quote(List.of(KIMCHI_ITEM), List.of());
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L)), "");
        givenEditRequest(current, editPlan);
        given(chatQuoteFacade.applyEditPlan(1L, 10L, current, editPlan))
                .willThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> reply("김치 빼줘"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(exception.getData()).isNull();
                });
        then(chatMessageService).should(never()).createFallbackMessage(anyLong(), anyLong(), any());
        assertThat(counter("zelo.chat.quote.busy")).isZero();
    }

    private ChatMessageResponse reply(String text) {
        return reply(List.of(), text);
    }

    private ChatMessageResponse reply(List<ChatMessage> history, String text) {
        return chatReplyCommandService.replyTo(10L, 1L, history, text, Function.identity());
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counters().stream().mapToDouble(Counter::count).sum();
    }

    private void givenPlan(MenuPlan menuPlan) {
        given(llmGateway.planMenu(anyString(), anyList(), anyString(), anyMap())).willReturn(planResult(menuPlan));
    }

    private void givenEditRequest(Quote current, QuoteEditPlan editPlan) {
        givenPlan(plan(MenuPlanIntent.QUOTE_EDIT));
        given(quoteService.findQuote(10L)).willReturn(Optional.of(current));
        given(llmGateway.planQuoteEdit(anyString(), anyList(), anyString(), anyMap()))
                .willReturn(LlmResult.of(editPlan, "gpt-test", 1, 1));
    }

    private void givenAssistantAnswer() {
        given(llmGateway.explain(anyString(), anyList(), anyString())).willReturn(LlmReply.of("답변", "gpt-test", 1, 1));
        givenAssistantMessage("답변");
    }

    private void givenAssistantMessage(String content) {
        given(chatMessageService.createAssistantMessage(10L, 1L, content))
                .willReturn(ChatMessage.of(10L, ChatSender.ASSISTANT, content));
    }

    private static ChatMessage cardMessage(String content) {
        return ChatMessage.of(10L, ChatSender.ASSISTANT, content, "{\"type\":\"QUOTE_CARD\"}");
    }

    private static ChatMessage fallbackMessage(String code) {
        String payload = "{\"type\":\"FALLBACK\",\"code\":\"" + code + "\"}";
        return ChatMessage.of(10L, ChatSender.ASSISTANT, "지금은 답변을 드리기 어려워요.", payload);
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

    private static MenuPlan plan(MenuPlanIntent intent) {
        if (intent != MenuPlanIntent.SHOPPING_REQUEST) {
            return MenuPlan.of(intent, "", 0, 0L, List.of(), List.of(), List.of());
        }
        return MenuPlan.of(intent, "김치볶음밥", 4, null, List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)),
                List.of(Keyword.NOT_SPICY), List.of());
    }

    private static LlmResult<MenuPlan> planResult(MenuPlan menuPlan) {
        return LlmResult.of(menuPlan, "gpt-test", 1, 1);
    }

    private static Quote quoteWithSubstitutes(String quoteId, List<QuoteItem> items) {
        long total = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        return new Quote(quoteId, 1L, 10L, "김치볶음밥", items, List.of(), total, now, now.plusMinutes(10),
                Set.of(), List.of(LEEK_SUBSTITUTE));
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
