package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.product.entity.Keyword;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 실제 OpenAI를 호출해 프롬프트대로 메모를 만드는지 확인한다. 요금이 나가므로 키가 있는 로컬에서만 직접 실행한다.
// CI는 저장소에 진짜 키가 있어도 돌리지 않는다(GitHub Actions는 CI=true를 넣는다)
// LLM 답은 매번 조금씩 달라서, 표기가 흔들릴 수 있는 메뉴 이름·재료 양은 확인하지 않는다
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.+")
@SpringBootTest(properties = {"zelo.llm.mode=real", "zelo.llm.timeout=30s"})
class MenuPlanOpenAiTest {

    // 검증·재시도까지 거친 결과를 본다
    @Autowired
    private LlmGateway llmGateway;

    @Autowired
    private MenuPlanPrompt menuPlanPrompt;

    @Test
    void 초등학생이라는_말이_있으면_조건에_맵지_않은이_들어간다() {
        // when
        MenuPlan menuPlan = planMenu("토요일에 초등학생 둘이랑 넷이 먹을 건데, 김치볶음밥 재료 3만 원 안에서 준비해 줘");

        // then
        assertThat(menuPlan.intent()).isEqualTo(MenuPlanIntent.SHOPPING_REQUEST);
        assertThat(menuPlan.servings()).isEqualTo(4);
        assertThat(menuPlan.budget()).isEqualTo(30_000L);
        assertThat(menuPlan.conditions()).contains(Keyword.NOT_SPICY);
        assertThat(menuPlan.ingredients())
                .extracting(MenuPlanIngredient::name)
                .isSubsetOf(MenuPlanPrompt.INGREDIENT_UNITS.keySet());
    }

    @Test
    void 키워드로_표현할_수_없는_대상은_추측하지_않고_그대로_남긴다() {
        // when
        MenuPlan menuPlan = planMenu("임산부가 먹기 좋은 김치볶음밥 2인분 재료 준비해 줘");

        // then
        assertThat(menuPlan.unmatchedConditions()).isNotEmpty();
    }

    @Test
    void 장보기가_아닌_말은_일반_질문으로_분류한다() {
        // when
        MenuPlan menuPlan = planMenu("안녕하세요");

        // then
        assertThat(menuPlan.intent()).isEqualTo(MenuPlanIntent.GENERAL_QUESTION);
    }

    private MenuPlan planMenu(String userText) {
        LlmResult<MenuPlan> result = llmGateway.planMenu(menuPlanPrompt.systemPrompt(), userText);
        System.out.println("[메뉴 계획] " + userText + " → " + result);
        return result.content();
    }
}
