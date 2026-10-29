package com.server.zelo.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MenuPlanPromptTest {

    private final MenuPlanPrompt menuPlanPrompt = new MenuPlanPrompt(new ClassPathResource("prompts/menu-plan.md"));

    @Test
    void 모든_조건_키워드에_한글_설명이_있다() {
        // then: 설명이 비면 프롬프트에 키워드 뜻 없이 이름만 들어간다
        assertThat(Keyword.values()).allSatisfy(keyword -> assertThat(keyword.getDescription()).isNotBlank());
    }

    @Test
    void 조건_키워드_목록을_enum에서_만들어_프롬프트에_넣는다() {
        // when
        String systemPrompt = menuPlanPrompt.systemPrompt(ingredientUnits(), null);

        // then
        for (Keyword keyword : Keyword.values()) {
            assertThat(systemPrompt).contains("- " + keyword + ": " + keyword.getDescription());
        }
    }

    @Test
    void 받은_재료_목록을_이름과_기본_단위로_받은_순서대로_넣는다() {
        // when
        String systemPrompt = menuPlanPrompt.systemPrompt(ingredientUnits(), null);

        // then
        assertThat(systemPrompt).contains("- 김치: G\n- 계란: EA\n- 대파: BUNCH");
    }

    @Test
    void 현재_견적이_있으면_그_메뉴_이름을_넣는다() {
        // when
        String systemPrompt = menuPlanPrompt.systemPrompt(ingredientUnits(), "김치볶음밥");

        // then
        assertThat(systemPrompt).contains("## 현재 견적 메뉴\n김치볶음밥\n");
    }

    @Test
    void 현재_견적이_없으면_없음으로_넣는다() {
        // when
        String systemPrompt = menuPlanPrompt.systemPrompt(ingredientUnits(), " ");

        // then
        assertThat(systemPrompt).contains("## 현재 견적 메뉴\n없음\n");
    }

    @Test
    void 견적_수정_판단_규칙과_앞_대화_유지_규칙이_들어_있다() {
        // when
        String systemPrompt = menuPlanPrompt.systemPrompt(ingredientUnits(), null);

        // then
        assertThat(systemPrompt)
                .contains("QUOTE_EDIT")
                .contains("현재 견적 메뉴가 \"없음\"이면 QUOTE_EDIT를 쓰지 마")
                .contains("인원(인분)을 바꾸는 말")
                .contains("앞 대화에서 말한 그대로 유지");
    }

    @Test
    void 개수_단위_재료는_그램이_아니라_사는_개수로_적는_규칙이_들어_있다() {
        // when
        String systemPrompt = menuPlanPrompt.systemPrompt(ingredientUnits(), null);

        // then
        assertThat(systemPrompt)
                .contains("EA·BUNCH 재료의 amount는 그램이 아니라 실제로 사는 개수야")
                .contains("콩나물밥 3인분의 쪽파(BUNCH) → 1");
    }

    @Test
    void 자리_표시가_모두_채워진다() {
        // when
        String systemPrompt = menuPlanPrompt.systemPrompt(ingredientUnits(), "김치볶음밥");

        // then
        assertThat(systemPrompt).doesNotContain("{{");
    }

    private static Map<String, BaseUnit> ingredientUnits() {
        Map<String, BaseUnit> ingredientUnits = new LinkedHashMap<>();
        ingredientUnits.put("김치", BaseUnit.G);
        ingredientUnits.put("계란", BaseUnit.EA);
        ingredientUnits.put("대파", BaseUnit.BUNCH);
        return ingredientUnits;
    }
}
