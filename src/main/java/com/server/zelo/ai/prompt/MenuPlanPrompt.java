package com.server.zelo.ai.prompt;

import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

// 메뉴 계획용 system 프롬프트를 만든다. 파일의 자리 표시를 재료 목록, 조건 키워드 목록, 현재 견적 메뉴로 채운다.
// Spring AI의 템플릿 기능({변수})을 쓰지 않고 직접 바꾸는 이유: 프롬프트에 중괄호가 들어가면 템플릿 해석이 깨질 수 있다
@Component
public class MenuPlanPrompt {

    private static final String NO_CURRENT_MENU = "없음";
    private static final String EXPIRED_MENU_FORMAT = "없음 (앞에서 만든 %s 견적은 시간이 지나 만료됨)";
    private static final String KEYWORDS_PLACEHOLDER = "{{KEYWORDS}}";
    private static final String INGREDIENTS_PLACEHOLDER = "{{INGREDIENTS}}";
    private static final String CURRENT_MENU_PLACEHOLDER = "{{CURRENT_MENU}}";

    private final String template;

    public MenuPlanPrompt(@Value("classpath:prompts/menu-plan.md") Resource template) {
        this.template = readTemplate(template).replace(KEYWORDS_PLACEHOLDER, keywordLines());
    }

    // 재료 목록은 상품 시드가 들어간 뒤의 재료 테이블 값을 써야 해서, 앱 시작 때가 아니라 요청마다 채운다.
    // 현재 견적 메뉴는 "4인분으로"처럼 메뉴를 말하지 않은 수정 요청과 새 메뉴 요청을 구분하는 데 쓴다
    public String systemPrompt(Map<String, BaseUnit> ingredientUnits, String currentMenuName) {
        return systemPrompt(ingredientUnits, currentMenuName, null);
    }

    // 만료된 견적 메뉴는 현재 견적이 없을 때만 쓴다. 처음부터 견적이 없던 방과 견적이 시간이 지나 사라진 방을 LLM이 구분하게 한다
    public String systemPrompt(Map<String, BaseUnit> ingredientUnits, String currentMenuName, String expiredMenuName) {
        return template
                .replace(INGREDIENTS_PLACEHOLDER, ingredientLines(ingredientUnits))
                .replace(CURRENT_MENU_PLACEHOLDER, currentMenu(currentMenuName, expiredMenuName));
    }

    private static String currentMenu(String currentMenuName, String expiredMenuName) {
        if (StringUtils.hasText(currentMenuName)) {
            return currentMenuName;
        }
        return StringUtils.hasText(expiredMenuName) ? EXPIRED_MENU_FORMAT.formatted(expiredMenuName) : NO_CURRENT_MENU;
    }

    // 견적 수정 프롬프트도 같은 모양의 재료 목록을 써서 같은 패키지에 연다
    static String ingredientLines(Map<String, BaseUnit> ingredientUnits) {
        return ingredientUnits.entrySet().stream()
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
}
