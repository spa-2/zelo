package com.server.zelo.domain.chat.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class QuoteCardPayloadTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 23, 12, 0);

    private final Quote quote = new Quote("quote-1", 9L, 10L, "김치볶음밥",
            List.of(new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 2, 24_000L)),
            List.of(new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED")),
            24_000L, CREATED_AT, CREATED_AT.plusMinutes(10));

    @Test
    void 견적에서_카드에_필요한_값과_예_아니오_버튼을_만든다() {
        // when
        QuoteCardPayload card = QuoteCardPayload.from(quote);

        // then
        assertThat(card.type()).isEqualTo("QUOTE_CARD");
        assertThat(card.quoteId()).isEqualTo("quote-1");
        assertThat(card.menuName()).isEqualTo("김치볶음밥");
        assertThat(card.items()).containsExactly(new QuoteCardPayload.Item(1L, "백김치", "1kg", 12_000L, 2, 24_000L));
        assertThat(card.excludedItems()).containsExactly(new QuoteCardPayload.Excluded("소주", "CATEGORY_NOT_ALLOWED"));
        assertThat(card.totalAmount()).isEqualTo(24_000L);
        assertThat(card.expiresAt()).isEqualTo(CREATED_AT.plusMinutes(10));
        assertThat(card.buttons()).extracting(QuoteCardPayload.Button::action).containsExactly("YES", "NO");
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
}
