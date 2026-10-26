package com.server.zelo.domain.chat.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.model.QuoteItemReason;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class QuoteCardPayloadTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 23, 12, 0);

    private final Quote quote = new Quote("quote-1", 9L, 10L, "김치볶음밥",
            List.of(new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 2, 24_000L)),
            List.of(new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED")),
            24_000L, CREATED_AT, CREATED_AT.plusMinutes(10));

    @Test
    void 견적에서_카드에_필요한_값을_만든다() {
        // when
        QuoteCardPayload card = QuoteCardPayload.from(quote);

        // then
        assertThat(card.type()).isEqualTo("QUOTE_CARD");
        assertThat(card.quoteId()).isEqualTo("quote-1");
        assertThat(card.menuName()).isEqualTo("김치볶음밥");
        assertThat(card.items()).containsExactly(new QuoteCardPayload.Item(1L, "백김치", "1kg", 12_000L, 2, 24_000L, null));
        assertThat(card.excludedItems()).containsExactly(new QuoteCardPayload.Excluded("소주", "CATEGORY_NOT_ALLOWED"));
        assertThat(card.totalAmount()).isEqualTo(24_000L);
        assertThat(card.expiresAt()).isEqualTo(CREATED_AT.plusMinutes(10));
    }

    @Test
    void JSON에는_사용자_ID_방_ID_상품_코드가_들어가지_않는다() {
        // when
        String json = JsonMapper.builder().build().writeValueAsString(QuoteCardPayload.from(quote));

        // then: 화면에 필요 없는 내부 값은 내보내지 않는다
        assertThat(json)
                .contains("\"type\":\"QUOTE_CARD\"")
                .contains("\"quoteId\":\"quote-1\"")
                .doesNotContain("userId")
                .doesNotContain("roomId")
                .doesNotContain("SID-0008");
    }

    @Test
    void 근거_JSON에는_화면에_보여_줄_칸만_들어간다() {
        // given
        QuoteItemReason reason = new QuoteItemReason(3, null, 0, Map.of(), null, Set.of(), false);

        // when
        String json = JsonMapper.builder().build().writeValueAsString(QuoteCardPayload.from(quoteWith(Set.of(), reason)));

        // then: 안내 문장용 isEmpty()가 "empty" 칸으로 나가지 않는다
        assertThat(json).contains("\"conditionSoldOut\":false").doesNotContain("\"empty\"");
    }

    @Test
    void 근거가_없는_견적이면_조건과_근거를_비워_둔다() {
        // when
        QuoteCardPayload card = QuoteCardPayload.from(quote);

        // then
        assertThat(card.conditions()).isEmpty();
        assertThat(card.items().get(0).reason()).isNull();
    }

    @Test
    void 맞은_조건_평점_언급_수_리뷰_요약을_근거로_보여_준다() {
        // given
        QuoteItemReason reason = new QuoteItemReason(120, new BigDecimal("4.53"), 23,
                Map.of(Keyword.NOT_SPICY, 15), "아삭하고 맵지 않다는 평이 많아요", Set.of(Keyword.NOT_SPICY), true);

        // when
        QuoteCardPayload card = QuoteCardPayload.from(quoteWith(Set.of(Keyword.NOT_SPICY), reason));

        // then: 평점은 소수 첫째 자리로 보여 준다
        assertThat(card.conditions()).containsExactly("맵지 않은");
        assertThat(card.items().get(0).reason()).isEqualTo(new QuoteCardPayload.Reason(
                List.of("맵지 않은"), false, new BigDecimal("4.5"), 23,
                List.of(new QuoteCardPayload.KeywordMention("맵지 않은", 15)), "아삭하고 맵지 않다는 평이 많아요"));
    }

    @Test
    void 리뷰가_5건_미만이면_평점과_리뷰_수를_보여_주지_않는다() {
        // given
        QuoteItemReason reason = new QuoteItemReason(3, new BigDecimal("5.00"), 4, Map.of(), null, Set.of(), false);

        // when
        QuoteCardPayload.Reason shown = QuoteCardPayload.from(quoteWith(Set.of(), reason)).items().get(0).reason();

        // then
        assertThat(shown.avgRating()).isNull();
        assertThat(shown.reviewCount()).isNull();
        assertThat(shown.isEmpty()).isTrue();
    }

    @Test
    void 조건과_상관없는_재료는_조건_근거와_0건_언급을_보여_주지_않는다() {
        // given: 즉석밥에는 "맵지 않은" 키워드가 붙은 후보가 없다
        QuoteItemReason reason = new QuoteItemReason(50, new BigDecimal("4.30"), 12,
                Map.of(Keyword.NOT_SPICY, 0), null, Set.of(), false);

        // when
        QuoteCardPayload card = QuoteCardPayload.from(quoteWith(Set.of(Keyword.NOT_SPICY), reason));

        // then: 요청 조건은 카드 위에 보이지만 이 상품의 근거에는 없다
        assertThat(card.conditions()).containsExactly("맵지 않은");
        QuoteCardPayload.Reason shown = card.items().get(0).reason();
        assertThat(shown.matchedConditions()).isEmpty();
        assertThat(shown.conditionSoldOut()).isFalse();
        assertThat(shown.keywordMentions()).isEmpty();
        assertThat(shown.avgRating()).isEqualByComparingTo("4.3");
    }

    @Test
    void 조건_상품이_품절이라_다른_상품을_골랐으면_알려_준다() {
        // given: 맵지 않은 김치는 있었지만 재고가 없어 다른 김치를 골랐다
        QuoteItemReason reason = new QuoteItemReason(80, new BigDecimal("4.10"), 9,
                Map.of(Keyword.NOT_SPICY, 0), null, Set.of(), true);

        // when
        QuoteCardPayload.Reason shown = QuoteCardPayload.from(quoteWith(Set.of(Keyword.NOT_SPICY), reason))
                .items().get(0).reason();

        // then
        assertThat(shown.conditionSoldOut()).isTrue();
        assertThat(shown.isEmpty()).isFalse();
    }

    @Test
    void 조건이_여럿이면_맞은_조건만_선언_순서대로_보여_준다() {
        // given
        Set<Keyword> conditions = Set.of(Keyword.DOMESTIC, Keyword.NOT_SPICY, Keyword.LOW_SALT);
        QuoteItemReason reason = new QuoteItemReason(120, new BigDecimal("4.50"), 23,
                Map.of(Keyword.DOMESTIC, 3, Keyword.NOT_SPICY, 15, Keyword.LOW_SALT, 0), null,
                Set.of(Keyword.DOMESTIC, Keyword.NOT_SPICY), true);

        // when
        QuoteCardPayload card = QuoteCardPayload.from(quoteWith(conditions, reason));

        // then
        assertThat(card.conditions()).containsExactly("맵지 않은", "저염", "국내산");
        QuoteCardPayload.Reason shown = card.items().get(0).reason();
        assertThat(shown.matchedConditions()).containsExactly("맵지 않은", "국내산");
        assertThat(shown.keywordMentions()).containsExactly(
                new QuoteCardPayload.KeywordMention("맵지 않은", 15),
                new QuoteCardPayload.KeywordMention("국내산", 3));
    }

    private static Quote quoteWith(Set<Keyword> conditions, QuoteItemReason reason) {
        return new Quote("quote-1", 9L, 10L, "김치볶음밥",
                List.of(new QuoteItem(1L, "SID-0008", "백김치", "1kg", 8_900L, 1, 8_900L, reason)),
                List.of(), 8_900L, CREATED_AT, CREATED_AT.plusMinutes(10), conditions);
    }
}
