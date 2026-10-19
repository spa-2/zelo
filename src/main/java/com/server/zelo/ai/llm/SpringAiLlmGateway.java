package com.server.zelo.ai.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "zelo.llm.mode", havingValue = "real")
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;

    public SpringAiLlmGateway(ChatClient.Builder chatClientBuilder) {
        // 모든 호출의 요청(system·user 메시지)과 응답을 DEBUG 로그로 남긴다
        this.chatClient = chatClientBuilder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Override
    public LlmReply explain(String systemPrompt, String userText) {
        // content()는 문장만 주므로, 토큰 사용량까지 받기 위해 chatResponse()를 쓴다
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
}
