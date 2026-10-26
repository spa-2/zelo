package com.server.zelo.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.ai.plan.QuoteEditAction;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class QuoteExplainPromptTest {

    private final QuoteExplainPrompt quoteExplainPrompt =
            new QuoteExplainPrompt(new ClassPathResource("prompts/quote-explain.md"));

    @Test
    void 견적_내용을_메뉴_인원_상품_합계_순서의_글로_만든다() {
        // given
        MenuPlan menuPlan = plan(null, List.of(), List.of());
        Quote quote = quote(List.of(
                new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 1, 12_000L),
                new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 2, 9_000L)), List.of());

        // when
        String text = quoteExplainPrompt.userText(menuPlan, quote);

        // then
        assertThat(text).isEqualTo("""
                메뉴: 김치볶음밥 (4인분)
                담은 상품:
                - 백김치 (1kg) 1개, 12,000원
                - 계란 (10구) 2개, 9,000원
                합계: 21,000원""");
    }

    @Test
    void 상품명에_단위가_이미_있으면_단위를_다시_쓰지_않는다() {
        // when
        String text = quoteExplainPrompt.userText(plan(null, List.of(), List.of()), quote(List.of(
                new QuoteItem(1L, "SID-0008", "백김치 1kg", "1kg", 8_900L, 1, 8_900L)), List.of()));

        // then
        assertThat(text).contains("- 백김치 1kg 1개, 8,900원");
    }

    @Test
    void 예산을_넘으면_넘은_금액을_서버가_계산해_적는다() {
        // when
        String text = quoteExplainPrompt.userText(plan(20_000L, List.of(), List.of()), quote(List.of(
                new QuoteItem(1L, "SID-0008", "백김치", "1kg", 21_000L, 1, 21_000L)), List.of()));

        // then
        assertThat(text).contains("예산: 20,000원 (예산을 1,000원 넘음)");
    }

    @Test
    void 예산_안이면_예산_안이라고_적는다() {
        // when
        String text = quoteExplainPrompt.userText(plan(30_000L, List.of(), List.of()), quote(List.of(
                new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 1, 12_000L)), List.of()));

        // then
        assertThat(text).contains("예산: 30,000원 (예산 안)");
    }

    @Test
    void 조건은_사용자_말_예시를_뺀_이름으로_적고_반영하지_못한_요청도_적는다() {
        // when
        String text = quoteExplainPrompt.userText(
                plan(null, List.of(Keyword.NOT_SPICY, Keyword.DOMESTIC), List.of("임산부가 먹기 좋은")),
                quote(List.of(), List.of()));

        // then
        assertThat(text)
                .contains("조건: 맵지 않은, 국내산\n")
                .contains("상품 조건으로 반영하지 못한 요청: 임산부가 먹기 좋은")
                .doesNotContain("(예:");
    }

    @Test
    void 빠진_재료는_이유를_한글로_적고_모르는_코드는_그대로_적는다() {
        // when
        String text = quoteExplainPrompt.userText(plan(null, List.of(), List.of()), quote(List.of(), List.of(
                new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED"),
                new ExcludedItem("트러플", "PRODUCT_NOT_FOUND"),
                new ExcludedItem("계란", "OUT_OF_STOCK"),
                new ExcludedItem("쌀", "NEW_REASON"))));

        // then
        assertThat(text)
                .contains("담은 상품: 없음")
                .contains("- 소주: AI가 대신 살 수 없는 품목(주류 등)")
                .contains("- 트러플: 판매하는 상품이 없음")
                .contains("- 계란: 재고 부족")
                .contains("- 쌀: NEW_REASON");
    }

    @Test
    void 고친_견적은_수정한_내용을_먼저_적고_고친_견적의_상품과_합계를_적는다() {
        // given: 뺀 상품의 이름은 고치기 전 견적에서 찾는다
        Quote before = quote(List.of(
                new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 1, 12_000L),
                new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 1, 4_500L)), List.of());
        Quote after = quote(List.of(new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 2, 9_000L)),
                List.of(new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED")));
        List<QuoteEdit> edits = List.of(
                QuoteEdit.of(QuoteEditAction.REMOVE, 1L, 0, "", 0, BaseUnit.EA),
                QuoteEdit.of(QuoteEditAction.CHANGE_QUANTITY, 2L, 2, "", 0, BaseUnit.EA),
                QuoteEdit.of(QuoteEditAction.ADD, 0L, 0, "소주", 360, BaseUnit.ML));

        // when
        String text = quoteExplainPrompt.editedText(before, after, edits, null, null);

        // then
        assertThat(text).isEqualTo("""
                메뉴: 김치볶음밥
                수정한 내용:
                - 백김치 (1kg) 빼기
                - 계란 (10구) 2개로 바꾸기
                - 소주 360ml 추가
                담은 상품:
                - 계란 (10구) 2개, 9,000원
                빠진 재료:
                - 소주: AI가 대신 살 수 없는 품목(주류 등)
                합계: 9,000원""");
    }

    @Test
    void 중간에_실패한_수정은_이유와_함께_하지_못한_수정으로_적는다() {
        // given
        Quote before = quote(List.of(new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 1, 4_500L)), List.of());
        QuoteEdit added = QuoteEdit.of(QuoteEditAction.ADD, 0L, 0, "대파", 1, BaseUnit.BUNCH);
        QuoteEdit failed = QuoteEdit.of(QuoteEditAction.CHANGE_QUANTITY, 2L, 50, "", 0, BaseUnit.EA);

        // when
        String text = quoteExplainPrompt.editedText(before, before, List.of(added), failed, ErrorCode.INSUFFICIENT_STOCK);

        // then
        assertThat(text).contains("""
                수정한 내용:
                - 대파 1단 추가
                하지 못한 수정:
                - 계란 (10구) 50개로 바꾸기: 재고 부족
                """);
    }

    @Test
    void 프롬프트에_수정한_내용과_하지_못한_수정을_안내하는_규칙이_있다() {
        // then
        assertThat(quoteExplainPrompt.systemPrompt())
                .contains("수정한 내용이 있으면 무엇을 바꿨는지 먼저")
                .contains("하지 못한 수정이 있으면 무엇을 왜 못 했는지");
    }

    @Test
    void 프롬프트에_숫자는_받은_그대로_쓰고_결제를_했다고_말하지_않는_규칙이_있다() {
        // then
        assertThat(quoteExplainPrompt.systemPrompt())
                .contains("받은 그대로만 써")
                .contains("[예] 버튼")
                .contains("결제를 했다고 말하지 마");
    }

    private static MenuPlan plan(Long budget, List<Keyword> conditions, List<String> unmatchedConditions) {
        return MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 4, budget,
                List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)), conditions, unmatchedConditions);
    }

    private static Quote quote(List<QuoteItem> items, List<ExcludedItem> excludedItems) {
        long total = items.stream().mapToLong(QuoteItem::lineAmount).sum();
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        return new Quote("quote-1", 1L, 10L, "김치볶음밥", items, excludedItems, total, now, now.plusMinutes(10));
    }
}
