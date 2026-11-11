package com.server.zelo.ai.gateway;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.product.entity.BaseUnit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

@Component
@Qualifier(ResilientLlmGateway.DELEGATE)
@ConditionalOnProperty(name = "zelo.llm.mode", havingValue = "real")
public class SpringAiLlmGateway implements LlmGateway {

    // 위조는 system 역할로 막고, 이 이름표는 LLM이 알림을 지시가 아닌 결제 결과로 읽게 한다(chat.md 규칙이 가리킴).
    // 지금 SYSTEM_EVENT는 결제 결과 알림뿐이다
    private static final String PAYMENT_NOTICE_LABEL = "결제 알림: ";
    // gpt-6-luna는 Chat Completions에서 도구를 추론 none일 때만 쓸 수 있다(기본 설정은 low)
    private static final String TOOL_REASONING_EFFORT = "none";
    // OpenAI가 출력 토큰 상한에 걸려 답을 끝까지 쓰지 못했을 때의 finish_reason (Spring AI가 값 이름을 대문자로 옮긴다)
    private static final String FINISH_REASON_LENGTH = "LENGTH";

    private final Duration timeout;
    private final ChatClient chatClient;
    private final String reviewSummaryModel;
    // 답 하나의 출력 토큰 상한. 추론 토큰도 포함된다
    private final Integer maxCompletionTokens;
    private final String reviewSummaryReasoningEffort;

    public SpringAiLlmGateway(
            ChatClient.Builder chatClientBuilder,
            @Value("${zelo.llm.timeout}") Duration timeout,
            @Value("${zelo.llm.max-completion-tokens}") Integer maxCompletionTokens,
            @Value("${zelo.llm.review-summary.model}") String reviewSummaryModel,
            @Value("${zelo.llm.review-summary.reasoning-effort}") String reviewSummaryReasoningEffort) {
        this.timeout = timeout;
        this.reviewSummaryModel = reviewSummaryModel;
        this.maxCompletionTokens = maxCompletionTokens;
        this.reviewSummaryReasoningEffort = reviewSummaryReasoningEffort;
        this.chatClient = chatClientBuilder
                // Spring AI 2.0.1은 요청마다 프롬프트 옵션의 timeout(기본 60초)으로 요청 타임아웃을 덮어써서
                // spring.ai.openai.timeout만으로는 적용되지 않는다. 그래서 모든 요청의 옵션에 직접 넣는다.
                // 시간 제한은 ResilientLlmGateway가 먼저 걸지만, 포기한 요청이 뒤에서 계속 연결을 붙잡지 않도록 HTTP에도 같은 값을 둔다
                // 요청에서 옵션을 따로 넣으면 이 기본 옵션이 통째로 빠지므로, 그런 요청은 timeout과 출력 토큰 상한을 다시 넣는다
                .defaultOptions(requestOptions())
                // 모든 호출의 요청(system·user 메시지)과 응답을 DEBUG 로그로 남긴다
                .defaultAdvisors(new SimpleLoggerAdvisor(), new TruncationGuard())
                .build();
    }

    @Override
    public LlmReply explain(String systemPrompt, List<ChatMessage> history, String userText) {
        return reply(chatClient.prompt().messages(toMessages(history)), systemPrompt, userText);
    }

    // 요청 옵션을 넣으면 기본 옵션이 통째로 빠지므로 timeout(빠지면 기본값 60초)과 출력 토큰 상한도 다시 넣는다
    // LOOKUP_QUESTION만 answerWithTools()를 거치고, GENERAL_QUESTION은 explain()(기본 추론, 도구 없음)으로 답하는 구조
    @Override
    public LlmReply answerWithTools(String systemPrompt, List<ChatMessage> history, String userText, Object tools,
                                    Map<String, Object> toolContext) {
        OpenAiChatOptions.Builder options = requestOptions()
                .reasoningEffort(TOOL_REASONING_EFFORT);
        return reply(chatClient.prompt().options(options).messages(toMessages(history))
                .tools(tools).toolContext(toolContext), systemPrompt, userText);
    }

    // 리뷰 요약도 문장 하나를 받는 호출이라 채팅 답변과 같은 방식으로 보내고, 리뷰 전용 system 프롬프트와 모델만 다르다.
    // 요청 옵션을 넣으면 기본 옵션이 통째로 빠지므로 timeout(빠지면 기본값 60초)과 출력 토큰 상한도 다시 넣는다
    @Override
    public LlmReply summarizeReviews(String systemPrompt, String reviewText) {
        OpenAiChatOptions.Builder options = requestOptions()
                .model(reviewSummaryModel)
                .reasoningEffort(reviewSummaryReasoningEffort);
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
        // 잘린 JSON은 객체로 바꾸기 전에 TruncationGuard가 AI_010으로 막는다
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

    private OpenAiChatOptions.Builder requestOptions() {
        return OpenAiChatOptions.builder().timeout(timeout).maxCompletionTokens(maxCompletionTokens);
    }

    // Spring AI는 system → 이 기록(순서·역할 그대로) → 이번 user 메시지 순서로 요청을 만든다.
    // 결제 결과 알림(SYSTEM_EVENT)은 그 자리에 system 역할로 보낸다. LLM 답은 항상 ASSISTANT, 사용자 말은 항상 USER로
    // 저장되므로, 어떤 글자를 써도 system 역할의 결제 알림으로 보이게 만들 수 없다
    private static List<Message> toMessages(List<ChatMessage> history) {
        return history.stream()
                .map(chatMessage -> switch (chatMessage.getSender()) {
                    case USER -> (Message) new UserMessage(chatMessage.getContent());
                    case ASSISTANT -> new AssistantMessage(chatMessage.getContent());
                    case SYSTEM_EVENT -> new SystemMessage(PAYMENT_NOTICE_LABEL + chatMessage.getContent());
                })
                .toList();
    }

    // 출력 토큰 상한에 걸려 잘린 답(finish_reason=length)을 쓰지 않고 AI_010으로 실패시킨다.
    // 구조화 출력은 이 어드바이저를 지난 뒤에 JSON을 객체로 바꾸므로, 여기서 막지 않으면 잘린 JSON이 형식 오류(AI_009)가 되어
    // 원인과 다른 "요청을 이해하지 못했어요"가 나가고, 같은 요청을 다시 보내 또 잘린다. 문장 답은 끊긴 문장이 그대로 저장되지 않게 한다.
    // 도구 호출 왕복은 모델 안에서 끝나므로 여기에는 마지막 답만 온다. 가장 바깥에 둬서 로그 어드바이저가 잘린 응답을 먼저 남기게 한다
    private static final class TruncationGuard implements CallAdvisor {

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
            ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);
            ChatResponse chatResponse = response.chatResponse();
            if (chatResponse != null && chatResponse.getResults().stream()
                    .anyMatch(generation -> FINISH_REASON_LENGTH.equals(generation.getMetadata().getFinishReason()))) {
                throw new BusinessException(ErrorCode.AI_RESPONSE_TRUNCATED);
            }
            return response;
        }

        @Override
        public String getName() {
            return TruncationGuard.class.getSimpleName();
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
