package com.server.zelo.ai.gateway;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.ai.prompt.QuoteExplainPrompt;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@Qualifier(ResilientLlmGateway.DELEGATE)
@ConditionalOnProperty(name = "zelo.llm.mode", havingValue = "fake")
public class FakeLlmGateway implements LlmGateway {

    public static final String MODEL = "fake";
    public static final String REVIEW_SUMMARY = "[FAKE] 리뷰 요약입니다.";
    public static final String EDIT_QUESTION = "[FAKE] 어떤 상품을 바꿀까요?";

    // 실제 OpenAI SDK와 같은 예외를 던져, 재시도 대상 구분까지 실제와 똑같이 확인할 수 있게 한다
    public enum Mode {
        NORMAL,
        DELAY, // 설정한 시간만큼 늦게 답한다 (타임아웃 확인)
        FAIL, // 서버 오류(5xx): 다시 보내면 나을 수 있어 재시도 대상
        REJECT // 요청 거절(4xx): 다시 보내도 똑같이 실패해 재시도하지 않음
    }

    // 테스트 스레드에서 바꾼 모드를 요청 스레드가 바로 보도록 volatile
    // 앱을 띄워서 느린 OpenAI·장애를 재현하려면(부하 테스트) zelo.llm.fake.mode=DELAY, zelo.llm.fake.delay=15s
    @Value("${zelo.llm.fake.mode:NORMAL}")
    private volatile Mode mode = Mode.NORMAL;
    @Value("${zelo.llm.fake.delay:6s}")
    private volatile Duration delay = Duration.ofSeconds(6);
    // 실제 응답 시간은 호출마다 달라서(메뉴 계획 p50 4.4~5.7초, 답변 p50 3.5초) delay~delay-max 사이에서 고른다. delay보다 크지 않으면 delay로 고정
    @Value("${zelo.llm.fake.delay-max:0s}")
    private volatile Duration delayMax = Duration.ZERO;
    // 기본은 일반 질문이라 채팅이 지금처럼 답변만 한다. 견적 흐름을 확인할 때만 테스트에서 바꾼다
    // 앱을 띄워서 견적까지 보려면(부하 테스트 등) zelo.llm.fake.plan-intent=SHOPPING_REQUEST
    @Value("${zelo.llm.fake.plan-intent:GENERAL_QUESTION}")
    private volatile MenuPlanIntent planIntent = MenuPlanIntent.GENERAL_QUESTION;// 기본은 무엇을 고칠지 되묻는 계획이라 견적을 바꾸지 않는다. 수정 흐름을 확인할 때만 테스트에서 바꾼다
    private volatile QuoteEditPlan quoteEditPlan = QuoteEditPlan.of(List.of(), EDIT_QUESTION);
    // 실제 OpenAI는 작업마다 응답 시간이 달라서(견적 안내 1~2초, 견적 수정 계획 5~8초) 작업별로 따로 줄 수 있게 한다.
    // 여기 없는 작업은 delay~delay-max를 쓴다. 장애 테스트(FakeLlmController)는 delay만 바꾸므로 이 값을 비워 두고 돌린다
    private volatile Map<LlmOperation, DelayRange> operationDelays = Map.of();
    // 부하 테스트에서 실제처럼 의도를 섞으려고 문장에 든 단어로 의도를 고른다. 적은 순서대로 보고, 맞는 게 없으면 planIntent
    private volatile List<IntentKeywords> intentKeywords = List.of();
    // explain은 견적 안내와 일반 답변이 같이 쓰므로, ResilientLlmGateway처럼 system 프롬프트로 둘을 나눈다
    @Autowired(required = false)
    private QuoteExplainPrompt quoteExplainPrompt;

    private record DelayRange(Duration min, Duration max) {
    }

    private record IntentKeywords(MenuPlanIntent intent, List<String> keywords) {
    }

    @Override
    public LlmReply explain(String systemPrompt, List<ChatMessage> history, String userText) {
        applyMode(explainOperation(systemPrompt));
        return answer(userText);
    }

    // 도구는 부르지 않고 explain과 같은 고정 답을 준다. 도구 호출 왕복은 SpringAiLlmGateway 테스트에서 확인한다
    @Override
    public LlmReply answerWithTools(String systemPrompt, List<ChatMessage> history, String userText, Object tools,
                                    Map<String, Object> toolContext) {
        applyMode(LlmOperation.LOOKUP_ANSWER);
        return answer(userText);
    }

    // 대화 기록과 관계없이 정해 둔 판단(intent-keywords에 맞는 단어가 있으면 그 의도, 없으면 planIntent)대로 같은 메모를 돌려준다.
    // 판단과 조건 키워드를 맞게 뽑는지는 실제 OpenAI 호출 테스트에서 확인한다
    @Override
    public LlmResult<MenuPlan> planMenu(String systemPrompt, List<ChatMessage> history, String userText,
                                        Map<String, BaseUnit> ingredientUnits) {
        applyMode(LlmOperation.MENU_PLAN);
        MenuPlanIntent intent = intentOf(userText);
        MenuPlan menuPlan = intent == MenuPlanIntent.SHOPPING_REQUEST
                ? MenuPlan.of(
                        MenuPlanIntent.SHOPPING_REQUEST,
                        "김치볶음밥",
                        4,
                        null,
                        List.of(
                                MenuPlanIngredient.of("김치", 400, BaseUnit.G),
                                MenuPlanIngredient.of("밥", 800, BaseUnit.G),
                                MenuPlanIngredient.of("계란", 4, BaseUnit.EA),
                                MenuPlanIngredient.of("대파", 1, BaseUnit.BUNCH)),
                        List.of(Keyword.NOT_SPICY),
                        List.of())
                : MenuPlan.of(intent, "", 0, 0L, List.of(), List.of(), List.of());
        return LlmResult.of(menuPlan, MODEL, 0, 0);
    }

    // 사용자 말·견적과 관계없이 정해 둔 수정 계획을 돌려준다
    @Override
    public LlmResult<QuoteEditPlan> planQuoteEdit(String systemPrompt, List<ChatMessage> history, String userText,
                                                  Map<String, BaseUnit> ingredientUnits) {
        applyMode(LlmOperation.QUOTE_EDIT_PLAN);
        return LlmResult.of(quoteEditPlan, MODEL, 0, 0);
    }

    @Override
    public LlmReply summarizeReviews(String systemPrompt, String reviewText) {
        applyMode(LlmOperation.REVIEW_SUMMARY);
        return LlmReply.of(REVIEW_SUMMARY, MODEL, 0, 0);
    }

    // 예: zelo.llm.fake.operation-delays=MENU_PLAN:1500ms-5300ms,QUOTE_EXPLAIN:1300ms-1800ms
    // 값은 delay처럼 1500ms, 2s 형식이고, 하나만 적으면(MENU_PLAN:3s) 그 시간으로 고정한다
    @Value("${zelo.llm.fake.operation-delays:}")
    public void changeOperationDelays(String spec) {
        Map<LlmOperation, DelayRange> delays = new EnumMap<>(LlmOperation.class);
        for (String entry : entries(spec)) {
            String[] operationAndRange = entry.split(":", 2);
            String[] range = operationAndRange[1].split("-", 2);
            Duration min = DurationStyle.detectAndParse(range[0].strip());
            Duration max = range.length > 1 ? DurationStyle.detectAndParse(range[1].strip()) : min;
            delays.put(LlmOperation.valueOf(operationAndRange[0].strip()), new DelayRange(min, max));
        }
        this.operationDelays = Map.copyOf(delays);
    }

    // 예: zelo.llm.fake.intent-keywords=QUOTE_EDIT:빼 줘|빼줘,LOOKUP_QUESTION:리뷰,GENERAL_QUESTION:보관
    @Value("${zelo.llm.fake.intent-keywords:}")
    public void changeIntentKeywords(String spec) {
        List<IntentKeywords> rules = new ArrayList<>();
        for (String entry : entries(spec)) {
            String[] intentAndKeywords = entry.split(":", 2);
            List<String> keywords = Arrays.stream(intentAndKeywords[1].split("\\|"))
                    .map(String::strip)
                    .filter(keyword -> !keyword.isEmpty())
                    .toList();
            rules.add(new IntentKeywords(MenuPlanIntent.valueOf(intentAndKeywords[0].strip()), keywords));
        }
        this.intentKeywords = List.copyOf(rules);
    }

    public void changeMode(Mode mode) {
        this.mode = mode;
    }

    public void changePlanIntent(MenuPlanIntent planIntent) {
        this.planIntent = planIntent;
    }

    public void changeQuoteEditPlan(QuoteEditPlan quoteEditPlan) {
        this.quoteEditPlan = quoteEditPlan;
    }

    public void changeDelay(Duration delay) {
        this.delay = delay;
    }

    public void changeDelayMax(Duration delayMax) {
        this.delayMax = delayMax;
    }

    private LlmReply answer(String userText) {
        return LlmReply.of("[FAKE] " + userText + "에 대한 답변입니다.", MODEL, 0, 0);
    }

    private LlmOperation explainOperation(String systemPrompt) {
        return quoteExplainPrompt != null && quoteExplainPrompt.systemPrompt().equals(systemPrompt)
                ? LlmOperation.QUOTE_EXPLAIN : LlmOperation.CHAT_ANSWER;
    }

    private MenuPlanIntent intentOf(String userText) {
        String text = userText == null ? "" : userText;
        return intentKeywords.stream()
                .filter(rule -> rule.keywords().stream().anyMatch(text::contains))
                .map(IntentKeywords::intent)
                .findFirst()
                .orElse(planIntent);
    }

    private static List<String> entries(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        return Arrays.stream(spec.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }

    private void applyMode(LlmOperation operation) {
        switch (mode) {
            case DELAY -> sleep(pickDelay(operation));
            case FAIL -> throw InternalServerException.builder()
                    .statusCode(500)
                    .headers(Headers.builder().build())
                    .build();
            case REJECT -> throw BadRequestException.builder()
                    .headers(Headers.builder().build())
                    .build();
            case NORMAL -> { }
        }
    }

    private Duration pickDelay(LlmOperation operation) {
        DelayRange range = operationDelays.get(operation);
        return range == null ? pickBetween(delay, delayMax) : pickBetween(range.min(), range.max());
    }

    private static Duration pickBetween(Duration min, Duration max) {
        if (max.compareTo(min) <= 0) {
            return min;
        }
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(min.toMillis(), max.toMillis() + 1));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("fake llm delay interrupted");
        }
    }
}
