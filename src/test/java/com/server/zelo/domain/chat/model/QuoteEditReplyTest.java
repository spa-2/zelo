package com.server.zelo.domain.chat.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;

class QuoteEditReplyTest {

    @ParameterizedTest
    @CsvSource({
            "QUOTE_EXPIRED, NO_QUOTE_TO_EDIT",
            "QUOTE_NOT_CURRENT, QUOTE_CHANGED",
            "PRODUCT_NOT_FOUND, NOT_IN_QUOTE",
            "INSUFFICIENT_STOCK, NOT_ENOUGH_STOCK"
    })
    void 사용자가_다시_하면_되는_견적_수정_오류는_정해진_문장으로_바꾼다(ErrorCode failure, QuoteEditReply expected) {
        // when & then
        assertThat(QuoteEditReply.from(failure)).contains(expected);
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = ErrorCode.class, names = {"SERVICE_UNAVAILABLE", "INTERNAL_ERROR", "TIMEOUT_EXCEPTION"})
    void 그_밖의_오류는_문장이_없어_부르는_쪽이_그대로_던진다(ErrorCode failure) {
        // when & then
        assertThat(QuoteEditReply.from(failure)).isEmpty();
    }
}
