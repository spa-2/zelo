package com.server.zelo.ai.llm;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.quote.model.Quote;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

// 견적 수정 계획용 system 프롬프트. 현재 견적 목록을 productId와 함께 넣어, LLM이 상품 이름이 아니라 ID로 고를 상품을 가리키게 한다
@Component
public class QuoteEditPrompt {

    private static final String QUOTE_PLACEHOLDER = "{{QUOTE}}";
    private static final String INGREDIENTS_PLACEHOLDER = "{{INGREDIENTS}}";

    private final String template;

    public QuoteEditPrompt(@Value("classpath:prompts/quote-edit.md") Resource template) {
        this.template = readTemplate(template);
    }

    // 추가는 재료 이름·단위로 하므로 메뉴 계획과 같은 재료 목록도 함께 넣는다
    public String systemPrompt(Quote quote, Map<String, BaseUnit> ingredientUnits) {
        return template
                .replace(QUOTE_PLACEHOLDER, quoteLines(quote))
                .replace(INGREDIENTS_PLACEHOLDER, MenuPlanPrompt.ingredientLines(ingredientUnits));
    }

    private static String quoteLines(Quote quote) {
        List<String> lines = new ArrayList<>();
        lines.add("메뉴: " + quote.menuName());
        quote.items().forEach(item -> lines.add("- productId " + item.productId() + ": " + item.productName()
                + " (판매 단위 " + item.productUnit() + ") " + item.quantity() + "개"));
        return String.join("\n", lines);
    }

    private static String readTemplate(Resource template) {
        try {
            return template.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
