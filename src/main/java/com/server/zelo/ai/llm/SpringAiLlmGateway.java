package com.server.zelo.ai.llm;

import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.product.entity.BaseUnit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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

    // chat.md 규칙이 이 표시로 서버가 남긴 결제 결과를 알아본다. 지금 SYSTEM_EVENT는 결제 결과 알림뿐이다
    private static final String PAYMENT_NOTICE_PREFIX = "[결제 알림] ";

    private final Duration timeout;
    private final ChatClient chatClient;
    private final String reviewSummaryModel;
    private final String reviewSummaryReasoningEffort;

    public SpringAiLlmGateway(
            ChatClient.Builder chatClientBuilder,
            @Value("${zelo.llm.timeout}") Duration timeout,
            @Value("${zelo.llm.review-summary.model}") String reviewSummaryModel,
            @Value("${zelo.llm.review-summary.reasoning-effort}") String reviewSummaryReasoningEffort) {
        this.timeout = timeout;
        this.reviewSummaryModel = reviewSummaryModel;
        this.reviewSummaryReasoningEffort = reviewSummaryReasoningEffort;
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
    public LlmReply explain(String systemPrompt, List<ChatMessage> history, String userText) {
        return reply(chatClient.prompt().messages(toMessages(history)), systemPrompt, userText);
    }

    // 리뷰 요약도 문장 하나를 받는 호출이라 채팅 답변과 같은 방식으로 보내고, 리뷰 전용 system 프롬프트와 모델만 다르다.
    // 요청 옵션을 넣으면 기본 옵션의 timeout이 기본값(60초)으로 덮이므로 timeout도 다시 넣는다
    @Override
    public LlmReply summarizeReviews(String systemPrompt, String reviewText) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(reviewSummaryModel)
                .reasoningEffort(reviewSummaryReasoningEffort)
                .timeout(timeout);
        return reply(chatClient.prompt().options(options), systemPrompt, reviewText);
    }

    private LlmReply reply(ChatClient.ChatClientRequestSpec request, String systemPrompt, String userText) {
        // content()는 문장만 주므로, 토큰 사용량까지 받기 위해 chatResponse()를 사용
        ChatResponse response = request
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

    // ingredientUnits는 프롬프트에 이미 들어가 있어 여기서는 쓰지 않는다. 검증은 ResilientLlmGateway가 한다
    @Override
    public LlmResult<MenuPlan> planMenu(String systemPrompt, List<ChatMessage> history, String userText,
                                        Map<String, BaseUnit> ingredientUnits) {
        return structuredResult(systemPrompt, history, userText, MenuPlan.class);
    }

    @Override
    public LlmResult<QuoteEditPlan> planQuoteEdit(String systemPrompt, List<ChatMessage> history, String userText,
                                                  Map<String, BaseUnit> ingredientUnits) {
        return structuredResult(systemPrompt, history, userText, QuoteEditPlan.class);
    }

    private <T> LlmResult<T> structuredResult(String systemPrompt, List<ChatMessage> history, String userText,
                                              Class<T> type) {
        // useProviderStructuredOutput: JSON 스키마를 프롬프트 글이 아니라 OpenAI 요청 옵션(엄격 모드)으로 보내,
        // 정해진 칸과 키워드·단위 목록 밖의 값이 오지 않게 한다. 객체로 바꾸지 못하면 JacksonException이 난다
        ResponseEntity<ChatResponse, T> response = chatClient.prompt()
                .system(systemPrompt)
                .messages(toMessages(history))
                .user(userText)
                .call()
                .responseEntity(type, ChatClient.EntityParamSpec::useProviderStructuredOutput);

        ChatResponseMetadata metadata = response.response().getMetadata();
        return LlmResult.of(
                response.entity(),
                metadata.getModel(),
                metadata.getUsage().getPromptTokens(),
                metadata.getUsage().getCompletionTokens());
    }

    // Spring AI는 system → 이 기록 → 이번 user 메시지 순서로 요청을 만든다.
    // 사용자가 아닌 메시지는 서비스 쪽이 한 말이라 AI 메시지로 보낸다. 결제 결과 알림(SYSTEM_EVENT)은 LLM이
    // 자기 말로 착각하지 않게 표시를 붙이고, 사용자가 같은 글자를 써도 user 메시지라 chat.md 규칙으로 구분된다
    private static List<Message> toMessages(List<ChatMessage> history) {
        return history.stream()
                .map(chatMessage -> switch (chatMessage.getSender()) {
                    case USER -> (Message) new UserMessage(chatMessage.getContent());
                    case ASSISTANT -> new AssistantMessage(chatMessage.getContent());
                    case SYSTEM_EVENT -> new AssistantMessage(PAYMENT_NOTICE_PREFIX + chatMessage.getContent());
                })
                .toList();
    }
}
