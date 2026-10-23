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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    @Mock
    private LlmGateway llmGateway;

    @Mock
    private ProductService productService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private QuoteCommandService quoteCommandService;

    // 실제 프롬프트 파일과 견적 글이 LLM으로 가는지 보려고 진짜 객체를 쓴다
    @Spy
    private ChatPrompt chatPrompt = new ChatPrompt(new ClassPathResource("prompts/chat.md"));

    @Spy
    private MenuPlanPrompt menuPlanPrompt = new MenuPlanPrompt(new ClassPathResource("prompts/menu-plan.md"));

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

        // then: 현재 견적 메뉴는 견적 조회(#110)가 생기기 전까지 "없음"
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
    void 견적_수정_판단이면_LLM과_견적_발급_없이_정해진_문장으로_답한다() {
        // given: 수정 도구가 생기기 전까지 AI가 고친 척 말하지 않게 한다
        givenUserMessageAndPlan(plan(MenuPlanIntent.QUOTE_EDIT));
        String notSupported = "지금은 견적을 부분만 고칠 수 없어요. 바꾸고 싶은 내용을 담아 다시 요청해 주세요.";
        given(chatMessageService.createAssistantMessage(10L, 1L, notSupported))
                .willReturn(ChatMessage.of(10L, ChatSender.ASSISTANT, notSupported));

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "계란 빼줘");

        // then
        assertThat(response.content()).isEqualTo(notSupported);
        then(llmGateway).should(never()).explain(any(), any(), any());
        then(quoteCommandService).should(never()).createQuote(any(), any(), any());
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
        long total = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        return new Quote("quote-1", 1L, 10L, "김치볶음밥", items, excludedItems, total, now, now.plusMinutes(10));
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
