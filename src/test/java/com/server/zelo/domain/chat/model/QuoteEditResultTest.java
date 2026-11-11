package com.server.zelo.domain.chat.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.ai.plan.QuoteEditAction;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.model.QuoteEditResult.Edited;
import com.server.zelo.domain.chat.model.QuoteEditResult.NotEdited;
import com.server.zelo.domain.chat.model.QuoteEditResult.PartiallyEdited;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.quote.model.Quote;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class QuoteEditResultTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);
    private static final Quote QUOTE = new Quote("quote-2", 1L, 10L, "김치볶음밥", List.of(), List.of(), 0L, NOW, NOW.plusMinutes(10));
    private static final QuoteEdit REMOVE_KIMCHI = QuoteEdit.of(QuoteEditAction.REMOVE, 1L, 0, "", 0, BaseUnit.EA);
    private static final QuoteEdit THREE_EGGS = QuoteEdit.of(QuoteEditAction.CHANGE_QUANTITY, 2L, 3, "", 0, BaseUnit.EA);

    @Test
    void 모두_적용한_결과는_적용한_수정이_하나도_없으면_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> new Edited(QUOTE, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Edited(QUOTE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Edited(null, List.of(REMOVE_KIMCHI))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 일부만_적용한_결과는_적용한_수정과_멈춘_수정과_이유가_모두_있어야_한다() {
        // when & then
        assertThatThrownBy(() -> new PartiallyEdited(QUOTE, List.of(), THREE_EGGS, ErrorCode.INSUFFICIENT_STOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartiallyEdited(QUOTE, List.of(REMOVE_KIMCHI), null, ErrorCode.INSUFFICIENT_STOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PartiallyEdited(QUOTE, List.of(REMOVE_KIMCHI), THREE_EGGS, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 일부만_적용한_결과는_안내할_수_없는_오류로도_만들_수_있다() {
        // when: 앞의 수정이 이미 저장됐으면 오류 종류와 관계없이 저장된 견적을 보여 줘야 한다
        PartiallyEdited partial = new PartiallyEdited(QUOTE, List.of(REMOVE_KIMCHI), THREE_EGGS, ErrorCode.SERVICE_UNAVAILABLE);

        // then
        assertThat(partial.failure()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"QUOTE_EXPIRED", "QUOTE_NOT_CURRENT", "PRODUCT_NOT_FOUND", "INSUFFICIENT_STOCK"})
    void 고치지_못한_결과는_사용자에게_안내할_수_있는_이유로_만든다(ErrorCode reason) {
        // when
        NotEdited notEdited = new NotEdited(reason);

        // then
        assertThat(notEdited.reason()).isEqualTo(reason);
    }

    @Test
    void 고치지_못한_결과는_안내할_수_없는_이유나_빈_이유로_만들_수_없다() {
        // when & then: 안내할 문장이 없는 오류는 결과가 아니라 예외로 던져야 한다
        assertThatThrownBy(() -> new NotEdited(ErrorCode.SERVICE_UNAVAILABLE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotEdited(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 적용한_수정_목록은_만든_뒤_바깥에서_바꿔도_바뀌지_않는다() {
        // given
        List<QuoteEdit> appliedEdits = new ArrayList<>(List.of(REMOVE_KIMCHI));
        Edited edited = new Edited(QUOTE, appliedEdits);

        // when
        appliedEdits.add(THREE_EGGS);

        // then
        assertThat(edited.appliedEdits()).containsExactly(REMOVE_KIMCHI);
        assertThatThrownBy(() -> edited.appliedEdits().add(THREE_EGGS)).isInstanceOf(UnsupportedOperationException.class);
    }
}
