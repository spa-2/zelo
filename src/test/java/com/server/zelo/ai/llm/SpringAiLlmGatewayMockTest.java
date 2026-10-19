package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

// OpenAI와 통신하는 ChatModel만 mock으로 바꾸고, ChatClient는 실제 객체로 거쳐 간다
@ExtendWith(MockitoExtension.class)
class SpringAiLlmGatewayMockTest {

    @Mock
    private ChatModel chatModel;

    private SpringAiLlmGateway springAiLlmGateway;

    @BeforeEach
    void setUp() {
        // ChatClient가 요청을 만들 때 모델의 기본 옵션을 복사하므로 null 대신 빈 옵션을 준다
        given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
        springAiLlmGateway = new SpringAiLlmGateway(ChatClient.builder(chatModel));
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

    private ChatResponse chatResponse(String text, String model, int promptTokens, int completionTokens) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder()
                        .model(model)
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build());
    }
}
