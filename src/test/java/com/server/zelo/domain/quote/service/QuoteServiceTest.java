package com.server.zelo.domain.quote.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.repository.QuoteRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    @Mock
    private QuoteRepository quoteRepository;

    @InjectMocks
    private QuoteService quoteService;

    private final Quote quote = new Quote("q-1", 1L, 5L, "김치볶음밥", List.of(), List.of(), 0L,
            LocalDateTime.now(), LocalDateTime.now().plusMinutes(10));


    @Test
    void 방의_현재_견적을_찾는다() {
        // given
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.of(quote));

        // when
        Optional<Quote> result = quoteService.findQuote(5L);

        // then
        assertThat(result).contains(quote);
    }

    @Test
    void 견적이_그대로면_견적을_돌려준다() {
        // given
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.of(quote));

        // when
        Quote result = quoteService.validateForOrder(5L, 1L, "q-1");

        // then
        assertThat(result).isEqualTo(quote);
    }

    @Test
    void 견적이_없으면_만료_예외가_발생한다() {
        // given
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> quoteService.validateForOrder(5L, 1L, "q-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.QUOTE_EXPIRED);
    }

    @Test
    void 다른_사람_견적이면_권한_예외가_발생한다() {
        // given
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.of(quote));

        // when & then
        assertThatThrownBy(() -> quoteService.validateForOrder(5L, 2L, "q-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NO_AUTHORITY);
    }

    @Test
    void 견적이_수정돼서_id가_다르면_예외가_발생한다() {
        // given
        given(quoteRepository.findByRoomId(5L)).willReturn(Optional.of(quote));

        // when & then
        assertThatThrownBy(() -> quoteService.validateForOrder(5L, 1L, "q-old"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.QUOTE_NOT_CURRENT);
    }
}