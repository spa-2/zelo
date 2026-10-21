package com.server.zelo.ai.llm;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

// 메뉴 계획용 system 프롬프트를 만든다. 파일의 자리 표시를 재료 목록과 조건 키워드 목록으로 채운다.
// Spring AI의 템플릿 기능({변수})을 쓰지 않고 직접 바꾸는 이유: 프롬프트에 중괄호가 들어가면 템플릿 해석이 깨질 수 있다
@Component
public class MenuPlanPrompt {

    // TODO(상품 담당 재료 시드 확정 후): ingredients 테이블에서 읽도록 교체. 그전까지 시연 메뉴 위주의 임시 목록
    public static final Map<String, BaseUnit> INGREDIENT_UNITS = temporaryIngredientUnits();

    private static final String INGREDIENTS_PLACEHOLDER = "{{INGREDIENTS}}";
    private static final String KEYWORDS_PLACEHOLDER = "{{KEYWORDS}}";

    private final String systemPrompt;

    public MenuPlanPrompt(@Value("classpath:prompts/menu-plan.md") Resource template) {
        this.systemPrompt = readTemplate(template)
                .replace(INGREDIENTS_PLACEHOLDER, ingredientLines())
                .replace(KEYWORDS_PLACEHOLDER, keywordLines());
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    private static String ingredientLines() {
        return INGREDIENT_UNITS.entrySet().stream()
                .map(entry -> "- " + entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    // Keyword enum을 돌며 만들어, 상품 쪽에 키워드가 추가되면 프롬프트에도 자동으로 들어간다
    private static String keywordLines() {
        return Arrays.stream(Keyword.values())
                .map(keyword -> "- " + keyword + ": " + keyword.getDescription())
                .collect(Collectors.joining("\n"));
    }

    private static String readTemplate(Resource template) {
        try {
            return template.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 프롬프트에 매번 같은 순서로 들어가도록 순서를 지키는 Map을 쓴다
    private static Map<String, BaseUnit> temporaryIngredientUnits() {
        Map<String, BaseUnit> units = new LinkedHashMap<>();
        units.put("김치", BaseUnit.G);
        units.put("밥", BaseUnit.G);
        units.put("계란", BaseUnit.EA);
        units.put("대파", BaseUnit.BUNCH);
        units.put("양파", BaseUnit.EA);
        units.put("햄", BaseUnit.G);
        units.put("돼지고기", BaseUnit.G);
        units.put("두부", BaseUnit.EA);
        units.put("식용유", BaseUnit.ML);
        units.put("간장", BaseUnit.ML);
        units.put("참기름", BaseUnit.ML);
        units.put("고추장", BaseUnit.G);
        units.put("소주", BaseUnit.ML);
        return Collections.unmodifiableMap(units);
    }
}
