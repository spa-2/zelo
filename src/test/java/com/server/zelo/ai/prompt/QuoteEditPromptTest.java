package com.server.zelo.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class QuoteEditPromptTest {

    private final QuoteEditPrompt quoteEditPrompt = new QuoteEditPrompt(new ClassPathResource("prompts/quote-edit.md"));

    @Test
    void 현재_견적의_메뉴와_상품을_productId와_함께_넣는다() {
        // when
        String systemPrompt = quoteEditPrompt.systemPrompt(quote(), ingredientUnits());

        // then
        assertThat(systemPrompt).contains("""
                ## 현재 견적
                메뉴: 김치볶음밥
                - productId 1: 백김치 (판매 단위 1kg) 1개
                - productId 2: 계란 (판매 단위 10구) 2개
                """);
    }

    @Test
    void 추가할_재료를_고를_재료_목록을_넣는다() {
        // when
        String systemPrompt = quoteEditPrompt.systemPrompt(quote(), ingredientUnits());

        // then
        assertThat(systemPrompt).contains("- 김치: G\n- 계란: EA");
    }

    @Test
    void 더하기와_개수_바꾸기를_구분하고_애매하면_되묻는_규칙이_있다() {
        // when
        String systemPrompt = quoteEditPrompt.systemPrompt(quote(), ingredientUnits());

        // then
        assertThat(systemPrompt)
                .contains("\"~ 더\", \"추가해 줘\"는 ADD, \"~개로\", \"~개만\"은 CHANGE_QUANTITY")
                .contains("현재 견적의 productId 중에서만 골라")
                .contains("edits를 빈 배열로 두고, question에 되물을 한 문장");
    }

    @Test
    void 자리_표시가_모두_채워진다() {
        // when
        String systemPrompt = quoteEditPrompt.systemPrompt(quote(), ingredientUnits());

        // then
        assertThat(systemPrompt).doesNotContain("{{");
    }

    private static Quote quote() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        List<QuoteItem> items = List.of(
                new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 1, 12_000L),
                new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 2, 9_000L));
        return new Quote("quote-1", 1L, 10L, "김치볶음밥", items, List.of(), 21_000L, now, now.plusMinutes(10));
    }

    private static Map<String, BaseUnit> ingredientUnits() {
        Map<String, BaseUnit> ingredientUnits = new LinkedHashMap<>();
        ingredientUnits.put("김치", BaseUnit.G);
        ingredientUnits.put("계란", BaseUnit.EA);
        return ingredientUnits;
    }
}
