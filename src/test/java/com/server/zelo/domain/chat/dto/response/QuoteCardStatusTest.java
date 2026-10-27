package com.server.zelo.domain.chat.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class QuoteCardStatusTest {

    @Test
    void 방의_현재_견적과_같은_카드는_ACTIVE다() {
        // when
        QuoteCardStatus status = QuoteCardStatus.of("B", "B", Set.of());

        // then
        assertThat(status).isEqualTo(QuoteCardStatus.ACTIVE);
    }

    @Test
    void 현재_견적과_다른_카드는_REPLACED다() {
        // when
        QuoteCardStatus status = QuoteCardStatus.of("A", "B", Set.of());

        // then
        assertThat(status).isEqualTo(QuoteCardStatus.REPLACED);
    }

    @Test
    void 방에_현재_견적이_없으면_EXPIRED다() {
        // when
        QuoteCardStatus status = QuoteCardStatus.of("B", null, Set.of());

        // then
        assertThat(status).isEqualTo(QuoteCardStatus.EXPIRED);
    }

    @Test
    void 주문한_카드는_현재_견적이든_만료됐든_ORDERED다() {
        // when & then
        assertThat(QuoteCardStatus.of("B", "B", Set.of("B"))).isEqualTo(QuoteCardStatus.ORDERED);
        assertThat(QuoteCardStatus.of("A", "B", Set.of("A"))).isEqualTo(QuoteCardStatus.ORDERED);
        assertThat(QuoteCardStatus.of("A", null, Set.of("A"))).isEqualTo(QuoteCardStatus.ORDERED);
    }
}
