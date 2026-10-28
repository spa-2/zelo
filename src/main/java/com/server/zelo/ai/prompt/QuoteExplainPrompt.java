package com.server.zelo.ai.prompt;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.model.SubstituteItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

// 견적 안내 문장용 프롬프트. 견적 내용은 서버가 글로 만들어 보내, 상품·가격·합계가 LLM이 아니라 서버 견적에서만 오게 한다
@Component
public class QuoteExplainPrompt {

    // 견적 담당의 ExcludedItem.reason 코드. 모르는 코드가 오면 코드 그대로 보낸다
    private static final Map<String, String> EXCLUDED_REASONS = Map.of(
            "CATEGORY_NOT_ALLOWED", "AI가 대신 살 수 없는 품목(주류 등)",
            "PRODUCT_NOT_FOUND", "판매하는 상품이 없음",
            "OUT_OF_STOCK", "재고 부족");
    // 견적 수정 메서드가 던진 오류 중 채팅이 안내하는 것. 그 밖의 오류는 ErrorCode 문구를 쓴다
    private static final Map<ErrorCode, String> EDIT_FAILURE_REASONS = Map.of(
            ErrorCode.INSUFFICIENT_STOCK, "재고 부족",
            ErrorCode.PRODUCT_NOT_FOUND, "견적에 없는 상품",
            ErrorCode.QUOTE_EXPIRED, "견적 만료");
    private static final Map<BaseUnit, String> UNIT_LABELS = Map.of(
            BaseUnit.G, "g", BaseUnit.ML, "ml", BaseUnit.EA, "개", BaseUnit.BUNCH, "단");

    private final String systemPrompt;

    public QuoteExplainPrompt(@Value("classpath:prompts/quote-explain.md") Resource template) {
        this.systemPrompt = readTemplate(template);
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    // 예산 초과 여부도 서버가 계산해서 적는다. LLM에게 숫자 비교를 맡기지 않는다
    public String userText(MenuPlan menuPlan, Quote quote) {
        List<String> lines = new ArrayList<>();
        lines.add("메뉴: " + quote.menuName() + " (" + menuPlan.servings() + "인분)");
        if (menuPlan.budget() != null) {
            lines.add("예산: " + won(menuPlan.budget()) + budgetResult(menuPlan.budget(), quote.totalAmount()));
        }
        if (!menuPlan.conditions().isEmpty()) {
            List<String> conditionNames = menuPlan.conditions().stream().map(Keyword::getLabel).toList();
            lines.add("조건: " + String.join(", ", conditionNames));
        }
        if (!menuPlan.unmatchedConditions().isEmpty()) {
            lines.add("상품 조건으로 반영하지 못한 요청: " + String.join(", ", menuPlan.unmatchedConditions()));
        }
        addQuoteLines(lines, quote, true);
        return String.join("\n", lines);
    }

    // 고친 견적의 안내용 글. 무엇을 고쳤는지는 LLM의 수정 계획이 아니라 실제로 실행한 수정만 적는다.
    // failedEdit·failure: 중간에 실패한 수정과 그 오류. 모두 고쳤으면 null
    public String editedText(Quote before, Quote after, List<QuoteEdit> appliedEdits,
                             QuoteEdit failedEdit, ErrorCode failure) {
        List<String> lines = new ArrayList<>();
        lines.add("메뉴: " + after.menuName());
        lines.add("수정한 내용:");
        appliedEdits.forEach(edit -> lines.add("- " + editLabel(before, edit)));
        if (failedEdit != null) {
            lines.add("하지 못한 수정:");
            lines.add("- " + editLabel(before, failedEdit) + ": "
                    + EDIT_FAILURE_REASONS.getOrDefault(failure, failure.getMessage()));
        }
        // 수정 안내는 무엇을 바꿨는지가 중요해서 고른 근거는 넣지 않는다
        addQuoteLines(lines, after, false);
        return String.join("\n", lines);
    }

    private static void addQuoteLines(List<String> lines, Quote quote, boolean withReasons) {
        lines.add("담은 상품:" + (quote.items().isEmpty() ? " 없음" : ""));
        for (QuoteItem item : quote.items()) {
            lines.add("- " + productLabel(item) + " " + item.quantity() + "개, " + won(item.lineAmount()));
            QuoteCardPayload.Reason reason = QuoteCardPayload.Reason.from(item.reason());
            if (withReasons && reason != null && !reason.isEmpty()) {
                lines.add("  근거: " + reasonText(reason));
            }
        }
        if (!quote.excludedItems().isEmpty()) {
            lines.add("빠진 재료:");
            for (ExcludedItem excludedItem : quote.excludedItems()) {
                lines.add("- " + excludedItem.name() + ": "
                        + EXCLUDED_REASONS.getOrDefault(excludedItem.reason(), excludedItem.reason()));
            }
        }
        if (!quote.substitutes().isEmpty()) {
            lines.add("대체 후보 (아직 담지 않음, 합계에 없음):");
            for (SubstituteItem substitute : quote.substitutes()) {
                QuoteItem item = substitute.item();
                lines.add("- " + substitute.name() + ": " + substitute.soldOutProductName() + " 품절 → "
                        + productLabel(item) + " " + item.quantity() + "개, " + won(item.lineAmount()));
            }
        }
        lines.add("합계: " + won(quote.totalAmount()));
    }

    // 뺀 상품은 고친 견적에 없으므로 고치기 전 견적에서 이름을 찾는다
    private static String editLabel(Quote before, QuoteEdit edit) {
        return switch (edit.action()) {
            case REMOVE -> productLabel(before, edit.productId()) + " 빼기";
            case CHANGE_QUANTITY -> productLabel(before, edit.productId()) + " " + edit.quantity() + "개로 바꾸기";
            case ADD -> edit.ingredientName() + " " + edit.amount() + UNIT_LABELS.get(edit.unit()) + " 추가";
        };
    }

    private static String productLabel(Quote quote, Long productId) {
        return quote.items().stream()
                .filter(item -> item.productId().equals(productId))
                .findFirst()
                .map(QuoteExplainPrompt::productLabel)
                .orElse("상품 " + productId);
    }

    // 상품명에 단위가 이미 들어 있으면(ex: "백김치 1kg") 같은 말을 두 번 쓰지 않는다.
    // 카드 버튼 수정의 정해진 문장(ChatMessageCommandService)도 같은 표기를 쓴다
    public static String productLabel(QuoteItem item) {
        return item.productName().contains(item.productUnit())
                ? item.productName()
                : item.productName() + " (" + item.productUnit() + ")";
    }

    private static String budgetResult(Long budget, Long totalAmount) {
        return totalAmount > budget ? " (예산을 " + won(totalAmount - budget) + " 넘음)" : " (예산 안)";
    }

    // 카드에 보이는 근거와 같은 값만 적어, 안내 문장이 카드에 없는 이유를 말하지 않게 한다
    private static String reasonText(QuoteCardPayload.Reason reason) {
        List<String> parts = new ArrayList<>();
        if (!reason.matchedConditions().isEmpty()) {
            parts.add("조건 맞음(" + String.join(", ", reason.matchedConditions()) + ")");
        }
        if (reason.conditionSoldOut()) {
            parts.add("조건에 맞는 상품이 재고가 없어 다른 상품으로 고름");
        }
        if (reason.avgRating() != null) {
            parts.add("평점 " + reason.avgRating().toPlainString() + " (리뷰 " + reason.reviewCount() + "개)");
        }
        reason.keywordMentions().forEach(mention ->
                parts.add("리뷰에서 '" + mention.condition() + "' 언급 " + mention.count() + "건"));
        if (reason.summary() != null) {
            parts.add("리뷰 요약: " + summaryData(reason.summary()));
        }
        return String.join(", ", parts);
    }

    // 리뷰 요약은 다른 사용자가 쓴 리뷰에서 나온 글이라, 안에 지시문이 있어도 데이터로만 읽히게 태그로 감싼다.
    // 꺾쇠괄호를 바꿔 두어 요약 안에 닫는 태그를 넣어 경계를 빠져나가지 못하게 한다
    private static String summaryData(String summary) {
        return "<review_summary>" + summary.replace("<", "&lt;").replace(">", "&gt;") + "</review_summary>";
    }

    private static String won(Long amount) {
        return String.format("%,d원", amount);
    }

    private static String readTemplate(Resource template) {
        try {
            return template.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
