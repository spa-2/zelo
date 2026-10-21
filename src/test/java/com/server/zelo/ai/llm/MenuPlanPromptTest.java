package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.product.entity.Keyword;
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
        String systemPrompt = menuPlanPrompt.systemPrompt();

        // then
        for (Keyword keyword : Keyword.values()) {
            assertThat(systemPrompt).contains("- " + keyword + ": " + keyword.getDescription());
        }
    }

    @Test
    void 재료_목록을_이름과_기본_단위로_프롬프트에_넣는다() {
        // when
        String systemPrompt = menuPlanPrompt.systemPrompt();

        // then
        MenuPlanPrompt.INGREDIENT_UNITS.forEach((name, unit) ->
                assertThat(systemPrompt).contains("- " + name + ": " + unit));
    }

    @Test
    void 자리_표시가_모두_채워진다() {
        // when
        String systemPrompt = menuPlanPrompt.systemPrompt();

        // then
        assertThat(systemPrompt).doesNotContain("{{");
    }
}
