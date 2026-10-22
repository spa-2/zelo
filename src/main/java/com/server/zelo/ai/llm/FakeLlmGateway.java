package com.server.zelo.ai.llm;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Qualifier(ResilientLlmGateway.DELEGATE)
@ConditionalOnProperty(name = "zelo.llm.mode", havingValue = "fake")
public class FakeLlmGateway implements LlmGateway {

    public static final String MODEL = "fake";
    public static final String REVIEW_SUMMARY = "[FAKE] 리뷰 요약입니다.";

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

    @Override
    public LlmReply explain(String systemPrompt, String userText) {
        applyMode();
        return LlmReply.of("[FAKE] " + userText + "에 대한 답변입니다.", MODEL, 0, 0);
    }

    // 사용자 말과 관계없이 항상 같은 메모를 돌려준다. 조건 키워드를 맞게 뽑는지는 실제 OpenAI 호출 테스트에서 확인한다
    @Override
    public LlmResult<MenuPlan> planMenu(String systemPrompt, String userText) {
        applyMode();
        MenuPlan menuPlan = MenuPlan.of(
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
                List.of());
        return LlmResult.of(menuPlan, MODEL, 0, 0);
    }

    @Override
    public LlmReply summarizeReviews(String systemPrompt, String reviewText) {
        applyMode();
        return LlmReply.of(REVIEW_SUMMARY, MODEL, 0, 0);
    }

    public void changeMode(Mode mode) {
        this.mode = mode;
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
