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

import com.server.zelo.ai.llm.ChatPrompt;
import com.server.zelo.ai.llm.LlmGateway;
import com.server.zelo.ai.llm.LlmReply;
import com.server.zelo.ai.llm.LlmResult;
import com.server.zelo.ai.llm.MenuPlan;
import com.server.zelo.ai.llm.MenuPlanIngredient;
import com.server.zelo.ai.llm.MenuPlanIntent;
import com.server.zelo.ai.llm.MenuPlanPrompt;
import com.server.zelo.ai.llm.QuoteEdit;
import com.server.zelo.ai.llm.QuoteEditAction;
import com.server.zelo.ai.llm.QuoteEditPlan;
import com.server.zelo.ai.llm.QuoteEditPrompt;
import com.server.zelo.ai.llm.QuoteExplainPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import com.server.zelo.domain.quote.service.QuoteService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChatMessageCommandServiceTest {

    private static final Map<String, BaseUnit> INGREDIENT_UNITS = Map.of("김치", BaseUnit.G, "계란", BaseUnit.EA);
    private static final QuoteItem KIMCHI_ITEM = new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 1, 12_000L);
    private static final QuoteItem EGG_ITEM = new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 1, 4_500L);
    private static final QuoteItem HAM_ITEM = new QuoteItem(3L, "MEA-0040", "햄", "200g", 3_900L, 1, 3_900L);
    private static final String NO_QUOTE_TO_EDIT = "고칠 견적이 없어요. 견적이 만료됐을 수 있으니 만들 메뉴를 다시 말씀해 주세요.";
    private static final String ASK_EDIT_AGAIN = "어떤 상품을 어떻게 바꿀지 다시 말씀해 주세요.";
    private static final String NOT_IN_QUOTE = "견적 카드에 없는 상품이라 고치지 못했어요. 카드에 있는 상품 이름으로 다시 말씀해 주세요.";
    private static final String NOT_ENOUGH_STOCK = "재고가 부족해서 그 수량으로 바꿀 수 없어요. 수량을 줄여서 다시 말씀해 주세요.";

    @Mock
    private LlmGateway llmGateway;

    @Mock
    private ProductService productService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private QuoteService quoteService;

    @Mock
    private QuoteCommandService quoteCommandService;

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
    private ChatMessageCommandService chatMessageCommandService;

    // 아래 기존 테스트들은 일반 질문 흐름이다. 메뉴 계획 판단을 일반 질문으로 깔아 두고, 견적 흐름 테스트에서만 바꾼다.
    // 메뉴 계획까지 가지 않는 테스트(방 권한 없음 등)도 있어 lenient로 둔다
    @BeforeEach
    void setUp() {
        lenient().when(productService.findIngredientUnits()).thenReturn(INGREDIENT_UNITS);
        lenient().when(llmGateway.planMenu(anyString(), anyList(), anyString(), anyMap()))
                .thenReturn(planResult(plan(MenuPlanIntent.GENERAL_QUESTION)));
    }

    @Test
    void 사용자_메시지_저장_LLM_호출_AI_메시지_저장_순서로_처리하고_AI_메시지를_반환한다() {
        // given
        given(chatMessageService.createUserMessage(10L, 1L, "김치볶음밥 재료"))
                .willReturn(userMessage(10L, 5L, "김치볶음밥 재료"));
        given(llmGateway.explain(anyString(), anyList(), eq("김치볶음밥 재료")))
                .willReturn(LlmReply.of("김치와 밥이 필요해요.", "gpt-test", 10, 5));
        given(chatMessageService.createAssistantMessage(10L, 1L, "김치와 밥이 필요해요."))
                .willReturn(ChatMessage.of(10L, ChatSender.ASSISTANT, "김치와 밥이 필요해요."));

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "김치볶음밥 재료");

        // then
        InOrder order = inOrder(chatMessageService, llmGateway);
        order.verify(chatMessageService).createUserMessage(10L, 1L, "김치볶음밥 재료");
        order.verify(llmGateway).explain(anyString(), anyList(), eq("김치볶음밥 재료"));
        order.verify(chatMessageService).createAssistantMessage(10L, 1L, "김치와 밥이 필요해요.");
        assertThat(response.sender()).isEqualTo(ChatSender.ASSISTANT);
        assertThat(response.content()).isEqualTo("김치와 밥이 필요해요.");
    }

    @Test
    void LLM을_쓸_수_없으면_고정_문구를_저장하고_그_실패_코드와_고정_문구_메시지를_담아_예외를_던진다() {
        // given
        given(chatMessageService.createUserMessage(10L, 1L, "안녕")).willReturn(userMessage(10L, 5L, "안녕"));
        given(llmGateway.explain(anyString(), anyList(), anyString()))
                .willThrow(new BusinessException(ErrorCode.AI_TIMEOUT));
        given(chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_TIMEOUT))
                .willReturn(fallbackMessage(10L, "AI_002"));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.sendMessage(10L, 1L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT);
                    assertThat(exception.getData()).isInstanceOfSatisfying(ChatMessageResponse.class, data -> {
                        assertThat(data.sender()).isEqualTo(ChatSender.ASSISTANT);
                        assertThat(data.payload()).isEqualTo("{\"type\":\"FALLBACK\",\"code\":\"AI_002\"}");
                    });
                });
        then(chatMessageService).should().createUserMessage(10L, 1L, "안녕");
        then(chatMessageService).should(never()).createAssistantMessage(anyLong(), anyLong(), anyString());
    }

    @Test
    void LLM_답을_저장하다_실패하면_고정_문구로_바꾸지_않고_그_오류를_그대로_던진다() {
        // given: LLM은 성공했지만 답을 저장하기 직전에 방 권한이 없어진 경우
        given(chatMessageService.createUserMessage(10L, 1L, "안녕")).willReturn(userMessage(10L, 5L, "안녕"));
        given(llmGateway.explain(anyString(), anyList(), anyString())).willReturn(LlmReply.of("답변", "gpt-test", 10, 5));
        given(chatMessageService.createAssistantMessage(10L, 1L, "답변"))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.sendMessage(10L, 1L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
                    assertThat(exception.getData()).isNull();
                });
        then(chatMessageService).should(never()).createFallbackMessage(anyLong(), anyLong(), any());
    }

    @Test
    void 방_접근_권한이_없으면_LLM을_호출하지_않는다() {
        // given
        given(chatMessageService.createUserMessage(10L, 2L, "안녕"))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.sendMessage(10L, 2L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
        then(llmGateway).should(never()).explain(any(), any(), any());
    }

    @Test
    void 새_대화는_방과_첫_메시지_저장_LLM_호출_AI_메시지_저장_순서로_처리하고_새_방_ID를_반환한다() {
        // given
        given(chatMessageService.createRoomWithFirstMessage(1L, "불고기 재료"))
                .willReturn(ChatMessage.of(77L, ChatSender.USER, "불고기 재료"));
        given(llmGateway.explain(anyString(), anyList(), eq("불고기 재료")))
                .willReturn(LlmReply.of("소고기와 간장이 필요해요.", "gpt-test", 10, 5));
        given(chatMessageService.createAssistantMessage(77L, 1L, "소고기와 간장이 필요해요."))
                .willReturn(ChatMessage.of(77L, ChatSender.ASSISTANT, "소고기와 간장이 필요해요."));

        // when
        StartChatResponse response = chatMessageCommandService.startChat(1L, "불고기 재료");

        // then
        InOrder order = inOrder(chatMessageService, llmGateway);
        order.verify(chatMessageService).createRoomWithFirstMessage(1L, "불고기 재료");
        order.verify(llmGateway).explain(anyString(), anyList(), eq("불고기 재료"));
        order.verify(chatMessageService).createAssistantMessage(77L, 1L, "소고기와 간장이 필요해요.");
        assertThat(response.roomId()).isEqualTo(77L);
        assertThat(response.message().sender()).isEqualTo(ChatSender.ASSISTANT);
        assertThat(response.message().content()).isEqualTo("소고기와 간장이 필요해요.");
    }

    @Test
    void 새_대화에서_LLM을_쓸_수_없으면_예외의_data에_새_방_ID와_고정_문구_메시지를_담는다() {
        // given
        given(chatMessageService.createRoomWithFirstMessage(1L, "안녕"))
                .willReturn(ChatMessage.of(77L, ChatSender.USER, "안녕"));
        given(llmGateway.explain(anyString(), anyList(), anyString()))
                .willThrow(new BusinessException(ErrorCode.AI_CIRCUIT_OPEN));
        given(chatMessageService.createFallbackMessage(77L, 1L, ErrorCode.AI_CIRCUIT_OPEN))
                .willReturn(fallbackMessage(77L, "AI_008"));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.startChat(1L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_CIRCUIT_OPEN);
                    assertThat(exception.getData()).isInstanceOfSatisfying(StartChatResponse.class, data -> {
                        assertThat(data.roomId()).isEqualTo(77L);
                        assertThat(data.message().payload()).contains("FALLBACK");
                    });
                });
        then(chatMessageService).should(never()).createAssistantMessage(anyLong(), anyLong(), anyString());
    }

    @Test
    void 새_대화의_첫_메시지_저장이_실패하면_LLM을_호출하지_않는다() {
        // given
        given(chatMessageService.createRoomWithFirstMessage(1L, " "))
                .willThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.startChat(1L, " "))
                .isInstanceOf(BusinessException.class);
        then(llmGateway).should(never()).explain(any(), any(), any());
    }

    @Test
    void 메시지를_보내면_방금_저장한_메시지_앞의_대화와_채팅_프롬프트를_LLM에_함께_보낸다() {
        // given
        List<ChatMessage> history = List.of(
                ChatMessage.of(10L, ChatSender.USER, "초등학생 둘이랑 셋이 김치볶음밥"),
                ChatMessage.of(10L, ChatSender.ASSISTANT, "김치, 밥, 계란이 필요해요."));
        given(chatMessageService.createUserMessage(10L, 1L, "그거 맵지 않게 해 줘"))
                .willReturn(userMessage(10L, 5L, "그거 맵지 않게 해 줘"));
        given(chatMessageService.findHistory(10L, 5L)).willReturn(history);
        given(llmGateway.explain(chatPrompt.systemPrompt(), history, "그거 맵지 않게 해 줘"))
                .willReturn(LlmReply.of("백김치로 준비할게요.", "gpt-test", 10, 5));
        given(chatMessageService.createAssistantMessage(10L, 1L, "백김치로 준비할게요."))
                .willReturn(ChatMessage.of(10L, ChatSender.ASSISTANT, "백김치로 준비할게요."));

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "그거 맵지 않게 해 줘");

        // then
        assertThat(response.content()).isEqualTo("백김치로 준비할게요.");
        assertThat(chatPrompt.systemPrompt()).contains("[예] 버튼");
    }

    @Test
    void 새_대화는_앞_대화가_없어_기록을_조회하지_않고_빈_기록으로_보낸다() {
        // given
        given(chatMessageService.createRoomWithFirstMessage(1L, "불고기 재료"))
                .willReturn(userMessage(77L, 1L, "불고기 재료"));
        given(llmGateway.explain(chatPrompt.systemPrompt(), List.of(), "불고기 재료"))
                .willReturn(LlmReply.of("소고기와 간장이 필요해요.", "gpt-test", 10, 5));
        given(chatMessageService.createAssistantMessage(77L, 1L, "소고기와 간장이 필요해요."))
                .willReturn(ChatMessage.of(77L, ChatSender.ASSISTANT, "소고기와 간장이 필요해요."));

        // when
        chatMessageCommandService.startChat(1L, "불고기 재료");

        // then
        then(chatMessageService).should(never()).findHistory(anyLong(), anyLong());
    }

    @Test
    void 메뉴_계획에는_재료_테이블_목록과_대화_기록과_이번_말을_보낸다() {
        // given
        List<ChatMessage> history = List.of(ChatMessage.of(10L, ChatSender.USER, "초등학생 둘이랑 셋이 김치볶음밥"));
        given(chatMessageService.createUserMessage(10L, 1L, "4인분으로 바꿔줘"))
                .willReturn(userMessage(10L, 5L, "4인분으로 바꿔줘"));
        given(chatMessageService.findHistory(10L, 5L)).willReturn(history);
        given(llmGateway.explain(anyString(), anyList(), anyString())).willReturn(LlmReply.of("답변", "gpt-test", 1, 1));
        given(chatMessageService.createAssistantMessage(10L, 1L, "답변"))
                .willReturn(ChatMessage.of(10L, ChatSender.ASSISTANT, "답변"));
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);

        // when
        chatMessageCommandService.sendMessage(10L, 1L, "4인분으로 바꿔줘");

        // then: 이 방에는 견적이 없어 현재 견적 메뉴는 "없음"
        then(llmGateway).should().planMenu(systemPrompt.capture(), eq(history), eq("4인분으로 바꿔줘"), eq(INGREDIENT_UNITS));
        assertThat(systemPrompt.getValue()).contains("- 김치: G").contains("## 현재 견적 메뉴\n없음");
    }

    @Test
    void 메뉴_계획이_실패하면_고정_문구로_응답하고_답변이나_견적을_만들지_않는다() {
        // given
        given(chatMessageService.createUserMessage(10L, 1L, "안녕")).willReturn(userMessage(10L, 5L, "안녕"));
        given(llmGateway.planMenu(anyString(), anyList(), anyString(), anyMap()))
                .willThrow(new BusinessException(ErrorCode.AI_INVALID_RESPONSE));
        given(chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_INVALID_RESPONSE))
                .willReturn(fallbackMessage(10L, "AI_009"));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.sendMessage(10L, 1L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE);
                    assertThat(exception.getData()).isInstanceOf(ChatMessageResponse.class);
                });
        then(llmGateway).should(never()).explain(any(), any(), any());
        then(quoteCommandService).should(never()).createQuote(any(), any(), any());
    }

    @Test
    void 장보기_요청이면_견적을_발급하고_안내_문장과_견적_카드를_함께_저장한다() {
        // given
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        Quote quote = quote(List.of(KIMCHI_ITEM), List.of());
        givenUserMessageAndPlan(menuPlan);
        given(quoteCommandService.createQuote(1L, 10L, menuPlan)).willReturn(quote);
        given(llmGateway.explain(quoteExplainPrompt.systemPrompt(), List.of(), quoteExplainPrompt.userText(menuPlan, quote)))
                .willReturn(LlmReply.of("맵지 않은 김치로 골랐어요. 주문하려면 [예]를 눌러 주세요.", "gpt-test", 1, 1));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "맵지 않은 김치로 골랐어요. 주문하려면 [예]를 눌러 주세요.",
                QuoteCardPayload.from(quote)))
                .willReturn(ChatMessage.of(10L, ChatSender.ASSISTANT, "맵지 않은 김치로 골랐어요. 주문하려면 [예]를 눌러 주세요.",
                        "{\"type\":\"QUOTE_CARD\"}"));

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "김치볶음밥 재료 준비해 줘");

        // then: 일반 채팅 프롬프트로는 답하지 않는다
        assertThat(response.payload()).contains("QUOTE_CARD");
        then(llmGateway).should(never()).explain(eq(chatPrompt.systemPrompt()), anyList(), anyString());
    }

    @Test
    void 견적_안내_문장을_받지_못해도_카드는_정해진_문장으로_저장하고_성공으로_응답한다() {
        // given
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        Quote quote = quote(List.of(KIMCHI_ITEM), List.of());
        givenUserMessageAndPlan(menuPlan);
        given(quoteCommandService.createQuote(1L, 10L, menuPlan)).willReturn(quote);
        given(llmGateway.explain(anyString(), anyList(), anyString())).willThrow(new BusinessException(ErrorCode.AI_TIMEOUT));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "견적을 준비했어요. 내용을 확인해 주세요.",
                QuoteCardPayload.from(quote)))
                .willReturn(ChatMessage.of(10L, ChatSender.ASSISTANT, "견적을 준비했어요. 내용을 확인해 주세요.",
                        "{\"type\":\"QUOTE_CARD\"}"));

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "김치볶음밥 재료 준비해 줘");

        // then
        assertThat(response.content()).isEqualTo("견적을 준비했어요. 내용을 확인해 주세요.");
        then(chatMessageService).should(never()).createFallbackMessage(anyLong(), anyLong(), any());
    }

    @Test
    void 담은_상품이_없으면_카드_없이_빠진_이유만_안내한다() {
        // given: 주문할 상품이 없는데 [예] 버튼이 뜨면 안 된다
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        Quote quote = quote(List.of(), List.of(new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED")));
        givenUserMessageAndPlan(menuPlan);
        given(quoteCommandService.createQuote(1L, 10L, menuPlan)).willReturn(quote);
        given(llmGateway.explain(quoteExplainPrompt.systemPrompt(), List.of(), quoteExplainPrompt.userText(menuPlan, quote)))
                .willReturn(LlmReply.of("소주는 AI가 대신 살 수 없어요.", "gpt-test", 1, 1));
        given(chatMessageService.createAssistantMessage(10L, 1L, "소주는 AI가 대신 살 수 없어요."))
                .willReturn(ChatMessage.of(10L, ChatSender.ASSISTANT, "소주는 AI가 대신 살 수 없어요."));

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "김치볶음밥 재료 준비해 줘");

        // then
        assertThat(response.payload()).isNull();
        then(chatMessageService).should(never()).createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void 담은_상품이_없을_때_안내도_받지_못하면_고정_문구로_응답한다() {
        // given
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        givenUserMessageAndPlan(menuPlan);
        given(quoteCommandService.createQuote(1L, 10L, menuPlan))
                .willReturn(quote(List.of(), List.of(new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED"))));
        given(llmGateway.explain(anyString(), anyList(), anyString())).willThrow(new BusinessException(ErrorCode.AI_TIMEOUT));
        given(chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_TIMEOUT))
                .willReturn(fallbackMessage(10L, "AI_002"));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.sendMessage(10L, 1L, "김치볶음밥 재료 준비해 줘"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT));
    }

    @Test
    void 견적_발급이_실패하면_고정_문구로_바꾸지_않고_그_오류를_그대로_던진다() {
        // given: LLM과 상관없는 서버 오류
        MenuPlan menuPlan = plan(MenuPlanIntent.SHOPPING_REQUEST);
        givenUserMessageAndPlan(menuPlan);
        given(quoteCommandService.createQuote(1L, 10L, menuPlan))
                .willThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.sendMessage(10L, 1L, "김치볶음밥 재료 준비해 줘"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(exception.getData()).isNull();
                });
        then(chatMessageService).should(never()).createFallbackMessage(anyLong(), anyLong(), any());
        then(llmGateway).should(never()).explain(any(), any(), any());
    }

    @Test
    void 현재_견적이_있으면_그_메뉴_이름을_메뉴_계획에_넣는다() {
        // given
        givenUserMessageAndPlan(plan(MenuPlanIntent.GENERAL_QUESTION));
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote(List.of(KIMCHI_ITEM), List.of())));
        givenAssistantAnswer();
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);

        // when
        chatMessageCommandService.sendMessage(10L, 1L, "4인분으로 바꿔줘");

        // then
        then(llmGateway).should().planMenu(systemPrompt.capture(), anyList(), anyString(), anyMap());
        assertThat(systemPrompt.getValue()).contains("## 현재 견적 메뉴\n김치볶음밥\n");
    }

    @Test
    void 상품이_하나도_없는_견적은_견적이_없는_것으로_본다() {
        // given: 견적 담당은 상품 0개 견적도 저장한다(예전 카드로 주문되지 않게 덮어쓰려고)
        givenUserMessageAndPlan(plan(MenuPlanIntent.GENERAL_QUESTION));
        given(quoteService.findQuote(10L))
                .willReturn(Optional.of(quote(List.of(), List.of(new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED")))));
        givenAssistantAnswer();
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);

        // when
        chatMessageCommandService.sendMessage(10L, 1L, "계란 빼줘");

        // then
        then(llmGateway).should().planMenu(systemPrompt.capture(), anyList(), anyString(), anyMap());
        assertThat(systemPrompt.getValue()).contains("## 현재 견적 메뉴\n없음\n");
    }

    @Test
    void 견적_수정_판단이어도_고칠_견적이_없으면_LLM과_견적_수정_없이_정해진_문장으로_답한다() {
        // given: LLM 답을 믿지 않는다. 견적이 만료됐을 수도 있다
        givenUserMessageAndPlan(plan(MenuPlanIntent.QUOTE_EDIT));
        givenAssistantMessage(NO_QUOTE_TO_EDIT);

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "계란 빼줘");

        // then
        assertThat(response.content()).isEqualTo(NO_QUOTE_TO_EDIT);
        then(llmGateway).should(never()).planQuoteEdit(any(), any(), any(), any());
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 수정_계획에는_현재_견적_목록과_재료_목록과_대화_기록과_이번_말을_보낸다() {
        // given
        List<ChatMessage> history = List.of(ChatMessage.of(10L, ChatSender.USER, "김치볶음밥 재료 준비해 줘"));
        Quote current = quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of());
        givenEditRequest(current, QuoteEditPlan.of(List.of(), "어떤 상품을 뺄까요?"));
        given(chatMessageService.findHistory(10L, 5L)).willReturn(history);
        givenAssistantMessage("어떤 상품을 뺄까요?");
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);

        // when
        chatMessageCommandService.sendMessage(10L, 1L, "그거 빼줘");

        // then
        then(llmGateway).should().planQuoteEdit(systemPrompt.capture(), eq(history), eq("그거 빼줘"), eq(INGREDIENT_UNITS));
        assertThat(systemPrompt.getValue())
                .contains("메뉴: 김치볶음밥")
                .contains("- productId 1: 백김치 (판매 단위 1kg) 1개")
                .contains("- productId 2: 계란 (판매 단위 10구) 1개")
                .contains("- 김치: G");
    }

    @Test
    void 견적_수정은_계획한_순서대로_고치고_마지막_견적으로_카드를_한_번만_만든다() {
        // given: "김치 빼고, 계란은 2개로, 햄 200g 추가해 줘"
        Quote current = quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of());
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L), changeQuantity(2L, 2), add("햄", 200, BaseUnit.G)), "");
        givenEditRequest(current, editPlan);
        QuoteItem twoEggs = new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 2, 9_000L);
        Quote added = quote("quote-4", List.of(twoEggs, HAM_ITEM));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willReturn(quote("quote-2", List.of(EGG_ITEM)));
        given(quoteCommandService.changeQuantity(1L, 10L, 2L, 2)).willReturn(quote("quote-3", List.of(twoEggs)));
        given(quoteCommandService.addItem(1L, 10L, "햄", 200, BaseUnit.G)).willReturn(added);
        String editedText = quoteExplainPrompt.editedText(current, added, editPlan.edits(), null, null);
        given(llmGateway.explain(quoteExplainPrompt.systemPrompt(), List.of(), editedText))
                .willReturn(LlmReply.of("김치를 빼고 햄을 더했어요.", "gpt-test", 1, 1));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "김치를 빼고 햄을 더했어요.", QuoteCardPayload.from(added)))
                .willReturn(cardMessage("김치를 빼고 햄을 더했어요."));

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "김치 빼고, 계란은 2개로, 햄 200g 추가해 줘");

        // then
        InOrder order = inOrder(quoteCommandService);
        order.verify(quoteCommandService).removeItem(1L, 10L, 1L);
        order.verify(quoteCommandService).changeQuantity(1L, 10L, 2L, 2);
        order.verify(quoteCommandService).addItem(1L, 10L, "햄", 200, BaseUnit.G);
        then(chatMessageService).should().createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
        assertThat(response.payload()).contains("QUOTE_CARD");
        assertThat(editedText)
                .contains("수정한 내용:\n- 백김치 (1kg) 빼기\n- 계란 (10구) 2개로 바꾸기\n- 햄 200g 추가")
                .doesNotContain("하지 못한 수정");
    }

    @Test
    void 무엇을_고칠지_정하지_못하면_견적을_고치지_않고_LLM이_되물은_문장으로_답한다() {
        // given
        givenEditRequest(quote(List.of(KIMCHI_ITEM), List.of()),
                QuoteEditPlan.of(List.of(), "포기김치와 백김치 중 어떤 김치를 뺄까요?"));
        givenAssistantMessage("포기김치와 백김치 중 어떤 김치를 뺄까요?");

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "김치 빼줘");

        // then
        assertThat(response.content()).isEqualTo("포기김치와 백김치 중 어떤 김치를 뺄까요?");
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 수정도_되묻는_문장도_없으면_정해진_문장으로_다시_물어본다() {
        // given
        givenEditRequest(quote(List.of(KIMCHI_ITEM), List.of()), QuoteEditPlan.of(List.of(), " "));
        givenAssistantMessage(ASK_EDIT_AGAIN);

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "바꿔줘");

        // then
        assertThat(response.content()).isEqualTo(ASK_EDIT_AGAIN);
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 하나라도_현재_견적에_없는_상품을_가리키면_아무것도_고치지_않는다() {
        // given: 계란 추가는 할 수 있지만 99번 상품이 견적에 없어 계란도 더하지 않는다
        givenEditRequest(quote(List.of(KIMCHI_ITEM), List.of()),
                QuoteEditPlan.of(List.of(add("계란", 2, BaseUnit.EA), remove(99L)), ""));
        givenAssistantMessage(NOT_IN_QUOTE);

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "계란 2개 더하고 쌀 빼줘");

        // then
        assertThat(response.content()).isEqualTo(NOT_IN_QUOTE);
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 첫_수정에서_재고가_부족하면_견적을_고치지_않고_정해진_문장으로_답한다() {
        // given
        givenEditRequest(quote(List.of(KIMCHI_ITEM), List.of()), QuoteEditPlan.of(List.of(changeQuantity(1L, 50)), ""));
        given(quoteCommandService.changeQuantity(1L, 10L, 1L, 50))
                .willThrow(new BusinessException(ErrorCode.INSUFFICIENT_STOCK));
        givenAssistantMessage(NOT_ENOUGH_STOCK);

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "김치 50개로");

        // then
        assertThat(response.content()).isEqualTo(NOT_ENOUGH_STOCK);
        then(llmGateway).should(never()).explain(any(), any(), any());
        then(chatMessageService).should(never()).createQuoteCardMessage(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void 견적이_그사이_만료되면_정해진_문장으로_답한다() {
        // given
        givenEditRequest(quote(List.of(KIMCHI_ITEM), List.of()), QuoteEditPlan.of(List.of(remove(1L)), ""));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willThrow(new BusinessException(ErrorCode.QUOTE_EXPIRED));
        givenAssistantMessage(NO_QUOTE_TO_EDIT);

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "김치 빼줘");

        // then
        assertThat(response.content()).isEqualTo(NO_QUOTE_TO_EDIT);
    }

    @Test
    void 앞의_수정이_저장된_뒤_실패하면_그_견적으로_카드를_만들고_하지_못한_수정을_안내한다() {
        // given: 김치는 이미 빠져 새 견적으로 저장됐으므로 그 견적을 보여 줘야 한다
        Quote current = quote(List.of(KIMCHI_ITEM, EGG_ITEM), List.of());
        Quote removed = quote("quote-2", List.of(EGG_ITEM));
        givenEditRequest(current, QuoteEditPlan.of(List.of(remove(1L), changeQuantity(2L, 50)), ""));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willReturn(removed);
        given(quoteCommandService.changeQuantity(1L, 10L, 2L, 50))
                .willThrow(new BusinessException(ErrorCode.INSUFFICIENT_STOCK));
        given(llmGateway.explain(eq(quoteExplainPrompt.systemPrompt()), eq(List.of()), anyString()))
                .willReturn(LlmReply.of("김치는 뺐지만 계란은 재고가 부족해요.", "gpt-test", 1, 1));
        given(chatMessageService.createQuoteCardMessage(10L, 1L, "김치는 뺐지만 계란은 재고가 부족해요.",
                QuoteCardPayload.from(removed)))
                .willReturn(cardMessage("김치는 뺐지만 계란은 재고가 부족해요."));
        ArgumentCaptor<String> editedText = ArgumentCaptor.forClass(String.class);

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "김치 빼고 계란 50개로");

        // then
        assertThat(response.payload()).contains("QUOTE_CARD");
        then(llmGateway).should().explain(anyString(), anyList(), editedText.capture());
        assertThat(editedText.getValue())
                .contains("수정한 내용:\n- 백김치 (1kg) 빼기")
                .contains("하지 못한 수정:\n- 계란 (10구) 50개로 바꾸기: 재고 부족");
    }

    @Test
    void 모든_상품을_빼면_카드_없이_안내만_한다() {
        // given
        givenEditRequest(quote(List.of(KIMCHI_ITEM), List.of()), QuoteEditPlan.of(List.of(remove(1L)), ""));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willReturn(quote("quote-2", List.of()));
        given(llmGateway.explain(eq(quoteExplainPrompt.systemPrompt()), eq(List.of()), anyString()))
                .willReturn(LlmReply.of("김치를 빼서 주문할 상품이 없어요.", "gpt-test", 1, 1));
        givenAssistantMessage("김치를 빼서 주문할 상품이 없어요.");

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "김치 빼줘");

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
        given(chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_TIMEOUT))
                .willReturn(fallbackMessage(10L, "AI_002"));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.sendMessage(10L, 1L, "김치 빼줘"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT);
                    assertThat(exception.getData()).isInstanceOf(ChatMessageResponse.class);
                });
        then(quoteCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 견적_수정_중_안내할_수_없는_오류는_고정_문구로_바꾸지_않고_그대로_던진다() {
        // given: LLM과 상관없는 서버 오류
        givenEditRequest(quote(List.of(KIMCHI_ITEM), List.of()), QuoteEditPlan.of(List.of(remove(1L)), ""));
        given(quoteCommandService.removeItem(1L, 10L, 1L)).willThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.sendMessage(10L, 1L, "김치 빼줘"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(exception.getData()).isNull();
                });
        then(chatMessageService).should(never()).createFallbackMessage(anyLong(), anyLong(), any());
    }

    private void givenEditRequest(Quote current, QuoteEditPlan editPlan) {
        givenUserMessageAndPlan(plan(MenuPlanIntent.QUOTE_EDIT));
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

    private static QuoteEdit remove(Long productId) {
        return QuoteEdit.of(QuoteEditAction.REMOVE, productId, 0, "", 0, BaseUnit.EA);
    }

    private static QuoteEdit changeQuantity(Long productId, Integer quantity) {
        return QuoteEdit.of(QuoteEditAction.CHANGE_QUANTITY, productId, quantity, "", 0, BaseUnit.EA);
    }

    private static QuoteEdit add(String ingredientName, Integer amount, BaseUnit unit) {
        return QuoteEdit.of(QuoteEditAction.ADD, 0L, 0, ingredientName, amount, unit);
    }

    private void givenUserMessageAndPlan(MenuPlan menuPlan) {
        given(chatMessageService.createUserMessage(eq(10L), eq(1L), anyString()))
                .willReturn(userMessage(10L, 5L, "메시지"));
        given(llmGateway.planMenu(anyString(), anyList(), anyString(), anyMap())).willReturn(planResult(menuPlan));
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

    private ChatMessage userMessage(Long roomId, Long messageId, String text) {
        ChatMessage message = ChatMessage.of(roomId, ChatSender.USER, text);
        ReflectionTestUtils.setField(message, "id", messageId);
        return message;
    }

    private ChatMessage fallbackMessage(Long roomId, String code) {
        String payload = "{\"type\":\"FALLBACK\",\"code\":\"" + code + "\"}";
        return ChatMessage.of(roomId, ChatSender.ASSISTANT, "지금은 답변을 드리기 어려워요.", payload);
    }
}
