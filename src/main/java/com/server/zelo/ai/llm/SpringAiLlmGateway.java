package com.server.zelo.ai.llm;

import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@Qualifier(ResilientLlmGateway.DELEGATE)
@ConditionalOnProperty(name = "zelo.llm.mode", havingValue = "real")
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;

    public SpringAiLlmGateway(ChatClient.Builder chatClientBuilder, @Value("${zelo.llm.timeout}") Duration timeout) {
        this.chatClient = chatClientBuilder
                // Spring AI 2.0.1은 요청마다 프롬프트 옵션의 timeout(기본 60초)으로 요청 타임아웃을 덮어써서
                // spring.ai.openai.timeout만으로는 적용되지 않는다. 그래서 모든 요청의 옵션에 직접 넣는다.
                // 시간 제한은 ResilientLlmGateway가 먼저 걸지만, 포기한 요청이 뒤에서 계속 연결을 붙잡지 않도록 HTTP에도 같은 값을 둔다
                .defaultOptions(OpenAiChatOptions.builder().timeout(timeout))
                // 모든 호출의 요청(system·user 메시지)과 응답을 DEBUG 로그로 남긴다
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Override
    public LlmReply explain(String systemPrompt, String userText) {
        // content()는 문장만 주므로, 토큰 사용량까지 받기 위해 chatResponse()를 사용
        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(userText)
                .call()
                .chatResponse();

        Usage usage = response.getMetadata().getUsage();
        return LlmReply.of(
                response.getResult().getOutput().getText(),
                response.getMetadata().getModel(),
                usage.getPromptTokens(),
                usage.getCompletionTokens());
    }

    @Override
    public LlmResult<MenuPlan> planMenu(String systemPrompt, String userText) {
        // useProviderStructuredOutput: JSON 스키마를 프롬프트 글이 아니라 OpenAI 요청 옵션(엄격 모드)으로 보내,
        // 정해진 칸과 키워드·단위 목록 밖의 값이 오지 않게 한다. 객체로 바꾸지 못하면 JacksonException이 난다
        ResponseEntity<ChatResponse, MenuPlan> response = chatClient.prompt()
                .system(systemPrompt)
                .user(userText)
                .call()
                .responseEntity(MenuPlan.class, ChatClient.EntityParamSpec::useProviderStructuredOutput);

        ChatResponseMetadata metadata = response.response().getMetadata();
        return LlmResult.of(
                response.entity(),
                metadata.getModel(),
                metadata.getUsage().getPromptTokens(),
                metadata.getUsage().getCompletionTokens());
    }
}
