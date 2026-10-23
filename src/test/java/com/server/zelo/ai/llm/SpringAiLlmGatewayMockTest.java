package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

// OpenAI와 통신하는 ChatModel만 mock으로 바꾸고, ChatClient는 실제 객체로 거쳐 간다
@ExtendWith(MockitoExtension.class)
class SpringAiLlmGatewayMockTest {

    // 테스트에서 쓰는 재료만 담은 재료 목록 (실제로는 재료 테이블에서 읽는다)
    private static final Map<String, BaseUnit> INGREDIENT_UNITS = Map.of(
            "김치", BaseUnit.G, "밥", BaseUnit.G, "계란", BaseUnit.EA, "대파", BaseUnit.BUNCH);

    @Mock
    private ChatModel chatModel;

    private SpringAiLlmGateway springAiLlmGateway;

    private static final Duration TIMEOUT = Duration.ofSeconds(7);

    @BeforeEach
    void setUp() {
        // ChatClient가 요청을 만들 때 모델의 기본 옵션을 복사하므로, 실제 OpenAiChatModel처럼 OpenAI 옵션을 준다
        given(chatModel.getOptions()).willReturn(OpenAiChatOptions.builder().build());
        springAiLlmGateway = new SpringAiLlmGateway(ChatClient.builder(chatModel), TIMEOUT, "gpt-review", "medium");
    }

    @Test
    void 응답에서_문장과_모델명과_토큰_수를_꺼내_반환한다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse("김치와 밥이 필요해요.", "gpt-test", 12, 7));

        // when
        LlmReply reply = springAiLlmGateway.explain("너는 장보기 도우미야", List.of(), "김치볶음밥 재료");

        // then
        assertThat(reply.text()).isEqualTo("김치와 밥이 필요해요.");
        assertThat(reply.model()).isEqualTo("gpt-test");
        assertThat(reply.promptTokens()).isEqualTo(12);
        assertThat(reply.completionTokens()).isEqualTo(7);
    }

    @Test
    void system_프롬프트와_사용자_말을_각각의_메시지로_전달한다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse("답변", "gpt-test", 1, 1));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);

        // when
        springAiLlmGateway.explain("너는 장보기 도우미야", List.of(), "김치볶음밥 재료");

        // then
        then(chatModel).should().call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        assertThat(prompt.getSystemMessage().getText()).isEqualTo("너는 장보기 도우미야");
        assertThat(prompt.getUserMessage().getText()).isEqualTo("김치볶음밥 재료");
    }

    @Test
    void 대화_기록을_system과_이번_사용자_말_사이에_보낸_사람대로_넣는다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse("답변", "gpt-test", 1, 1));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        List<ChatMessage> history = List.of(
                ChatMessage.of(10L, ChatSender.USER, "초등학생 둘이랑 셋이 김치볶음밥"),
                ChatMessage.of(10L, ChatSender.ASSISTANT, "김치, 밥, 계란이 필요해요."));

        // when
        springAiLlmGateway.explain("system", history, "그거 맵지 않게 해 줘");

        // then
        then(chatModel).should().call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getInstructions())
                .extracting(Message::getMessageType, Message::getText)
                .containsExactly(
                        tuple(MessageType.SYSTEM, "system"),
                        tuple(MessageType.USER, "초등학생 둘이랑 셋이 김치볶음밥"),
                        tuple(MessageType.ASSISTANT, "김치, 밥, 계란이 필요해요."),
                        tuple(MessageType.USER, "그거 맵지 않게 해 줘"));
    }

    @Test
    void 메뉴_계획에도_대화_기록을_같은_순서로_넣는다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse(MENU_PLAN_JSON, "gpt-test", 1, 1));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        List<ChatMessage> history = List.of(ChatMessage.of(10L, ChatSender.USER, "초등학생 둘이랑 셋이 김치볶음밥"));

        // when
        springAiLlmGateway.planMenu("메뉴 계획 프롬프트", history, "4인분으로 바꿔줘", INGREDIENT_UNITS);

        // then
        then(chatModel).should().call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getInstructions())
                .extracting(Message::getMessageType, Message::getText)
                .containsExactly(
                        tuple(MessageType.SYSTEM, "메뉴 계획 프롬프트"),
                        tuple(MessageType.USER, "초등학생 둘이랑 셋이 김치볶음밥"),
                        tuple(MessageType.USER, "4인분으로 바꿔줘"));
    }

    @Test
    void 설정한_타임아웃을_모든_요청의_옵션에_넣는다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse("답변", "gpt-test", 1, 1));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);

        // when
        springAiLlmGateway.explain("system", List.of(), "질문");

        // then: OpenAiChatModel은 이 값으로 요청 타임아웃을 정한다
        then(chatModel).should().call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions())
                .isInstanceOfSatisfying(OpenAiChatOptions.class,
                        options -> assertThat(options.getTimeout()).isEqualTo(TIMEOUT));
    }

    @Test
    void 리뷰_요약은_리뷰_전용_system_프롬프트와_리뷰_글을_보내고_문장과_토큰_수를_돌려준다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse("맵지 않아 아이들이 잘 먹는다는 평이 많아요", "gpt-test", 200, 30));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);

        // when
        LlmReply reply = springAiLlmGateway.summarizeReviews("리뷰 요약 프롬프트", "상품: 백김치\n리뷰:\n- [5점] 안 매워요");

        // then
        then(chatModel).should().call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getSystemMessage().getText()).isEqualTo("리뷰 요약 프롬프트");
        assertThat(promptCaptor.getValue().getUserMessage().getText()).isEqualTo("상품: 백김치\n리뷰:\n- [5점] 안 매워요");
        assertThat(reply.text()).isEqualTo("맵지 않아 아이들이 잘 먹는다는 평이 많아요");
        assertThat(reply.model()).isEqualTo("gpt-test");
        assertThat(reply.promptTokens()).isEqualTo(200);
        assertThat(reply.completionTokens()).isEqualTo(30);
    }

    @Test
    void 리뷰_요약은_리뷰_요약용_모델과_추론_강도로_보내고_타임아웃은_그대로_둔다() {
        // given
        given(chatModel.call(any(Prompt.class))).willReturn(chatResponse("요약", "gpt-review", 1, 1));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);

        // when
        springAiLlmGateway.summarizeReviews("리뷰 요약 프롬프트", "리뷰");

        // then
        then(chatModel).should().call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions())
                .isInstanceOfSatisfying(OpenAiChatOptions.class, options -> {
                    assertThat(options.getModel()).isEqualTo("gpt-review");
                    assertThat(options.getReasoningEffort()).isEqualTo("medium");
                    assertThat(options.getTimeout()).isEqualTo(TIMEOUT);
                });
    }

    @Test
    void 메뉴_계획_JSON_응답을_MenuPlan으로_바꾸고_모델명과_토큰_수를_함께_돌려준다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse(MENU_PLAN_JSON, "gpt-test", 300, 120));

        // when
        LlmResult<MenuPlan> result = springAiLlmGateway.planMenu("메뉴 계획 프롬프트", List.of(), "초등학생 둘이랑 넷이 김치볶음밥", INGREDIENT_UNITS);

        // then
        MenuPlan menuPlan = result.content();
        assertThat(menuPlan.intent()).isEqualTo(MenuPlanIntent.SHOPPING_REQUEST);
        assertThat(menuPlan.menuName()).isEqualTo("김치볶음밥");
        assertThat(menuPlan.servings()).isEqualTo(4);
        assertThat(menuPlan.budget()).isNull(); // 예산 0은 "말하지 않음"
        assertThat(menuPlan.ingredients()).containsExactly(
                MenuPlanIngredient.of("김치", 400, BaseUnit.G),
                MenuPlanIngredient.of("계란", 4, BaseUnit.EA));
        assertThat(menuPlan.conditions()).containsExactly(Keyword.NOT_SPICY);
        assertThat(menuPlan.unmatchedConditions()).containsExactly("임산부가 먹기 좋은");
        assertThat(result.model()).isEqualTo("gpt-test");
        assertThat(result.promptTokens()).isEqualTo(300);
        assertThat(result.completionTokens()).isEqualTo(120);
    }

    @Test
    void 메뉴_계획은_JSON_스키마를_사용자_말에_붙이지_않고_요청_옵션으로_보낸다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse(MENU_PLAN_JSON, "gpt-test", 1, 1));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);

        // when
        springAiLlmGateway.planMenu("메뉴 계획 프롬프트", List.of(), "김치볶음밥 재료", INGREDIENT_UNITS);

        // then
        then(chatModel).should().call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        assertThat(prompt.getSystemMessage().getText()).isEqualTo("메뉴 계획 프롬프트");
        assertThat(prompt.getUserMessage().getText()).isEqualTo("김치볶음밥 재료");
        assertThat(prompt.getOptions()).isInstanceOfSatisfying(OpenAiChatOptions.class, options -> {
            // OpenAiChatModel은 이 스키마를 response_format(json_schema, 엄격 모드)으로 보낸다
            assertThat(options.getOutputSchema()).contains("\"unmatchedConditions\"").contains("\"NOT_SPICY\"");
            assertThat(options.getTimeout()).isEqualTo(TIMEOUT);
        });
    }

    @Test
    void 메뉴_계획_응답이_JSON이_아니면_AI_INVALID_RESPONSE로_분류되는_예외를_던진다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse("죄송해요, 다시 말씀해 주세요.", "gpt-test", 1, 1));

        // when & then
        assertThatThrownBy(() -> springAiLlmGateway.planMenu("system", List.of(), "질문", INGREDIENT_UNITS))
                .satisfies(exception -> assertThat(LlmErrorClassifier.classify(exception))
                        .isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
    }

    @Test
    void 수정_계획_JSON_응답을_QuoteEditPlan으로_바꾸고_스키마는_요청_옵션으로_보낸다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse(QUOTE_EDIT_PLAN_JSON, "gpt-test", 200, 40));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);

        // when
        LlmResult<QuoteEditPlan> result = springAiLlmGateway.planQuoteEdit("수정 계획 프롬프트", List.of(), "김치 빼고 계란 2개 더", INGREDIENT_UNITS);

        // then
        assertThat(result.content().edits()).containsExactly(
                QuoteEdit.of(QuoteEditAction.REMOVE, 1L, 0, "", 0, BaseUnit.EA),
                QuoteEdit.of(QuoteEditAction.ADD, 0L, 0, "계란", 2, BaseUnit.EA));
        assertThat(result.content().question()).isEmpty();
        assertThat(result.promptTokens()).isEqualTo(200);
        then(chatModel).should().call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getUserMessage().getText()).isEqualTo("김치 빼고 계란 2개 더");
        assertThat(promptCaptor.getValue().getOptions()).isInstanceOfSatisfying(OpenAiChatOptions.class, options -> {
            assertThat(options.getOutputSchema()).contains("\"edits\"").contains("\"CHANGE_QUANTITY\"");
            assertThat(options.getTimeout()).isEqualTo(TIMEOUT);
        });
    }

    @Test
    void 수정_계획에도_대화_기록을_system과_이번_말_사이에_넣는다() {
        // given
        given(chatModel.call(any(Prompt.class))).willReturn(chatResponse(QUOTE_EDIT_PLAN_JSON, "gpt-test", 1, 1));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        List<ChatMessage> history = List.of(ChatMessage.of(10L, ChatSender.ASSISTANT, "백김치로 준비했어요."));

        // when
        springAiLlmGateway.planQuoteEdit("수정 계획 프롬프트", history, "그거 빼줘", INGREDIENT_UNITS);

        // then
        then(chatModel).should().call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getInstructions())
                .extracting(Message::getMessageType, Message::getText)
                .containsExactly(
                        tuple(MessageType.SYSTEM, "수정 계획 프롬프트"),
                        tuple(MessageType.ASSISTANT, "백김치로 준비했어요."),
                        tuple(MessageType.USER, "그거 빼줘"));
    }

    private static final String QUOTE_EDIT_PLAN_JSON = """
            {"edits":[
              {"action":"REMOVE","productId":1,"quantity":0,"ingredientName":"","amount":0,"unit":"EA"},
              {"action":"ADD","productId":0,"quantity":0,"ingredientName":"계란","amount":2,"unit":"EA"}],
             "question":""}
            """;

    private static final String MENU_PLAN_JSON = """
            {"intent":"SHOPPING_REQUEST","menuName":"김치볶음밥","servings":4,"budget":0,
             "ingredients":[{"name":"김치","amount":400,"unit":"G"},{"name":"계란","amount":4,"unit":"EA"}],
             "conditions":["NOT_SPICY"],"unmatchedConditions":["임산부가 먹기 좋은"]}
            """;

    private ChatResponse chatResponse(String text, String model, int promptTokens, int completionTokens) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder()
                        .model(model)
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build());
    }
}
