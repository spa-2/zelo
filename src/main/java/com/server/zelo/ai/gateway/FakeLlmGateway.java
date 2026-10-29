package com.server.zelo.ai.gateway;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    private volatile Mode mode = Mode.NORMAL;
    private volatile Duration delay = Duration.ofSeconds(6);
    // 기본은 일반 질문이라 채팅이 지금처럼 답변만 한다. 견적 흐름을 확인할 때만 테스트에서 바꾼다
    // 앱을 띄워서 견적까지 보려면(부하 테스트 등) zelo.llm.fake.plan-intent=SHOPPING_REQUEST
    @Value("${zelo.llm.fake.plan-intent:GENERAL_QUESTION}")
    private volatile MenuPlanIntent planIntent = MenuPlanIntent.GENERAL_QUESTION;// 기본은 무엇을 고칠지 되묻는 계획이라 견적을 바꾸지 않는다. 수정 흐름을 확인할 때만 테스트에서 바꾼다
    private volatile QuoteEditPlan quoteEditPlan = QuoteEditPlan.of(List.of(), EDIT_QUESTION);

    @Override
    public LlmReply explain(String systemPrompt, List<ChatMessage> history, String userText) {
        applyMode();
        return LlmReply.of("[FAKE] " + userText + "에 대한 답변입니다.", MODEL, 0, 0);
    }

    // 사용자 말·대화 기록과 관계없이 정해 둔 판단(planIntent)대로 같은 메모를 돌려준다.
    // 판단과 조건 키워드를 맞게 뽑는지는 실제 OpenAI 호출 테스트에서 확인한다
    @Override
    public LlmResult<MenuPlan> planMenu(String systemPrompt, List<ChatMessage> history, String userText,
                                        Map<String, BaseUnit> ingredientUnits) {
        applyMode();
        MenuPlan menuPlan = planIntent == MenuPlanIntent.SHOPPING_REQUEST
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
                : MenuPlan.of(planIntent, "", 0, 0L, List.of(), List.of(), List.of());
        return LlmResult.of(menuPlan, MODEL, 0, 0);
    }

    // 사용자 말·견적과 관계없이 정해 둔 수정 계획을 돌려준다
    @Override
    public LlmResult<QuoteEditPlan> planQuoteEdit(String systemPrompt, List<ChatMessage> history, String userText,
                                                  Map<String, BaseUnit> ingredientUnits) {
        applyMode();
        return LlmResult.of(quoteEditPlan, MODEL, 0, 0);
    }

    @Override
    public LlmReply summarizeReviews(String systemPrompt, String reviewText) {
        applyMode();
        return LlmReply.of(REVIEW_SUMMARY, MODEL, 0, 0);
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

    private void applyMode() {
        switch (mode) {
            case DELAY -> sleep(delay);
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

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("fake llm delay interrupted");
        }
    }
}
