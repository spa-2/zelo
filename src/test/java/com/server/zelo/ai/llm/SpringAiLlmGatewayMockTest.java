package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
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

    @Mock
    private ChatModel chatModel;

    private SpringAiLlmGateway springAiLlmGateway;

    private static final Duration TIMEOUT = Duration.ofSeconds(7);

    @BeforeEach
    void setUp() {
        // ChatClient가 요청을 만들 때 모델의 기본 옵션을 복사하므로, 실제 OpenAiChatModel처럼 OpenAI 옵션을 준다
        given(chatModel.getOptions()).willReturn(OpenAiChatOptions.builder().build());
        springAiLlmGateway = new SpringAiLlmGateway(ChatClient.builder(chatModel), TIMEOUT);
    }

    @Test
    void 응답에서_문장과_모델명과_토큰_수를_꺼내_반환한다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse("김치와 밥이 필요해요.", "gpt-test", 12, 7));

        // when
        LlmReply reply = springAiLlmGateway.explain("너는 장보기 도우미야", "김치볶음밥 재료");

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
        springAiLlmGateway.explain("너는 장보기 도우미야", "김치볶음밥 재료");

        // then
        then(chatModel).should().call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        assertThat(prompt.getSystemMessage().getText()).isEqualTo("너는 장보기 도우미야");
        assertThat(prompt.getUserMessage().getText()).isEqualTo("김치볶음밥 재료");
    }

    @Test
    void 설정한_타임아웃을_모든_요청의_옵션에_넣는다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse("답변", "gpt-test", 1, 1));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);

        // when
        springAiLlmGateway.explain("system", "질문");

        // then: OpenAiChatModel은 이 값으로 요청 타임아웃을 정한다
        then(chatModel).should().call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions())
                .isInstanceOfSatisfying(OpenAiChatOptions.class,
                        options -> assertThat(options.getTimeout()).isEqualTo(TIMEOUT));
    }

    @Test
    void 메뉴_계획_JSON_응답을_MenuPlan으로_바꾸고_모델명과_토큰_수를_함께_돌려준다() {
        // given
        given(chatModel.call(any(Prompt.class)))
                .willReturn(chatResponse(MENU_PLAN_JSON, "gpt-test", 300, 120));

        // when
        LlmResult<MenuPlan> result = springAiLlmGateway.planMenu("메뉴 계획 프롬프트", "초등학생 둘이랑 넷이 김치볶음밥");

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
        springAiLlmGateway.planMenu("메뉴 계획 프롬프트", "김치볶음밥 재료");

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
        assertThatThrownBy(() -> springAiLlmGateway.planMenu("system", "질문"))
                .satisfies(exception -> assertThat(LlmErrorClassifier.classify(exception))
                        .isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
    }

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
