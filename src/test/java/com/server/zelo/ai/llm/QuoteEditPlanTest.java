package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuoteEditPlanTest {

    private static final Map<String, BaseUnit> INGREDIENT_UNITS = Map.of("김치", BaseUnit.G, "계란", BaseUnit.EA);

    @Test
    void 빼기_수량_변경_추가가_모두_맞으면_검증을_통과한다() {
        // given
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L), changeQuantity(2L, 3), add("계란", 2, BaseUnit.EA)), "");

        // when & then
        assertThatCode(() -> editPlan.validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 되묻기만_하는_빈_계획도_검증을_통과한다() {
        // when & then
        assertThatCode(() -> QuoteEditPlan.of(List.of(), "어떤 김치를 뺄까요?").validate(INGREDIENT_UNITS))
                .doesNotThrowAnyException();
    }

    @Test
    void 목록에_없는_재료_추가는_검증_오류가_아니다() {
        // when & then: 견적 담당 addItem이 "상품 없음"으로 빠진 재료에 넣는다
        assertThatCode(() -> QuoteEditPlan.of(List.of(add("트러플", 10, BaseUnit.G)), "").validate(INGREDIENT_UNITS))
                .doesNotThrowAnyException();
    }

    @Test
    void 수정_목록이나_되묻기_칸이_없으면_검증에_실패한다() {
        // when & then
        assertInvalid(QuoteEditPlan.of(null, ""));
        assertInvalid(QuoteEditPlan.of(List.of(), null));
    }

    @Test
    void 수정이_10개를_넘으면_검증에_실패한다() {
        // when & then
        assertInvalid(QuoteEditPlan.of(Collections.nCopies(11, remove(1L)), ""));
    }

    @Test
    void 빼기와_수량_변경은_상품_ID가_있어야_한다() {
        // when & then
        assertInvalid(QuoteEditPlan.of(List.of(remove(0L)), ""));
        assertInvalid(QuoteEditPlan.of(List.of(changeQuantity(null, 2)), ""));
    }

    @Test
    void 수량_변경은_1개_이상이어야_한다() {
        // when & then: 0개로 바꾸는 말은 빼기로 정리해야 한다
        assertInvalid(QuoteEditPlan.of(List.of(changeQuantity(1L, 0)), ""));
    }

    @Test
    void 추가는_이름_양_단위가_맞아야_하고_목록에_있는_재료는_단위가_같아야_한다() {
        // when & then
        assertInvalid(QuoteEditPlan.of(List.of(add("", 2, BaseUnit.EA)), ""));
        assertInvalid(QuoteEditPlan.of(List.of(add("계란", 0, BaseUnit.EA)), ""));
        assertInvalid(QuoteEditPlan.of(List.of(add("계란", 100, BaseUnit.G)), ""));
    }

    @Test
    void 동작이_없으면_검증에_실패한다() {
        // when & then
        assertInvalid(QuoteEditPlan.of(List.of(QuoteEdit.of(null, 1L, 0, "", 0, BaseUnit.EA)), ""));
    }

    @Test
    void 빼기와_수량_변경이_모두_현재_견적의_상품이면_적용할_수_있다() {
        // given: 추가는 상품 ID 없이 재료 이름으로 하므로 확인하지 않는다
        QuoteEditPlan editPlan = QuoteEditPlan.of(List.of(remove(1L), changeQuantity(2L, 3), add("햄", 200, BaseUnit.G)), "");

        // when & then
        assertThat(editPlan.isApplicableTo(quote())).isTrue();
    }

    @Test
    void 하나라도_현재_견적에_없는_상품이면_적용할_수_없다() {
        // when & then
        assertThat(QuoteEditPlan.of(List.of(remove(1L), changeQuantity(99L, 3)), "").isApplicableTo(quote())).isFalse();
    }

    @Test
    void 한_상품을_두_번_고치면_적용할_수_없다() {
        // when & then: 빼고 나서 수량을 바꾸면 두 번째 수정이 실패한다
        assertThat(QuoteEditPlan.of(List.of(remove(1L), changeQuantity(1L, 3)), "").isApplicableTo(quote())).isFalse();
    }

    private static void assertInvalid(QuoteEditPlan editPlan) {
        assertThatThrownBy(() -> editPlan.validate(INGREDIENT_UNITS))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
    }

    private static Quote quote() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        List<QuoteItem> items = List.of(
                new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 1, 12_000L),
                new QuoteItem(2L, "MEA-0025", "계란", "10구", 4_500L, 1, 4_500L));
        return new Quote("quote-1", 1L, 10L, "김치볶음밥", items, List.of(), 16_500L, now, now.plusMinutes(10));
    }

    private static QuoteEdit remove(Long productId) {
        return QuoteEdit.of(QuoteEditAction.REMOVE, productId, 0, "", 0, BaseUnit.EA);
    }

    private static QuoteEdit changeQuantity(Long productId, Integer quantity) {
        return QuoteEdit.of(QuoteEditAction.CHANGE_QUANTITY, productId, quantity, "", 0, BaseUnit.EA);
    }

    private static QuoteEdit add(String ingredientName, Integer amount, BaseUnit unit) {
        return QuoteEdit.of(QuoteEditAction.ADD, 0L, 0, ingredientName, amount, unit);
    }
}
