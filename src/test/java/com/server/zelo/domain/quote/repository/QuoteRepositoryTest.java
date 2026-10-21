package com.server.zelo.domain.quote.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class QuoteRepositoryTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private QuoteRepository quoteRepository;

    @BeforeEach
    void setUp() {
        quoteRepository = new QuoteRepository(JsonMapper.builder().build(), stringRedisTemplate);
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
    }

    @Test
    void 견적을_채팅방_키에_10분으로_저장한다() {
        // given
        Quote quote = quote(5L);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

        // when
        quoteRepository.save(quote);

        // then
        then(valueOperations).should().set(eq("quote:5"), json.capture(), eq(QuoteRepository.QUOTE_TTL));
        assertThat(json.getValue()).contains("\"quoteId\":\"q-1\"", "\"menuName\":\"김치볶음밥\"");
    }

    @Test
    void 저장한_견적을_채팅방_ID로_다시_읽는다() {
        // given
        Quote quote = quote(5L);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        quoteRepository.save(quote);
        then(valueOperations).should().set(anyString(), json.capture(), eq(QuoteRepository.QUOTE_TTL));
        given(valueOperations.get("quote:5")).willReturn(json.getValue());

        // when
        Optional<Quote> result = quoteRepository.findByRoomId(5L);

        // then
        assertThat(result).contains(quote);
    }

    @Test
    void 견적이_없으면_빈_값을_반환한다() {
        // given
        given(valueOperations.get("quote:5")).willReturn(null);

        // when
        Optional<Quote> result = quoteRepository.findByRoomId(5L);

        // then
        assertThat(result).isEmpty();
    }

    private Quote quote(Long roomId) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 21, 18, 0);
        return new Quote("q-1", 12L, roomId, "김치볶음밥",
                List.of(new QuoteItem(3L, "P-KIMCHI-001", "백김치 1kg", "1kg", 8900L, 1, 8900L)),
                List.of(new ExcludedItem("소주", "CATEGORY_NOT_ALLOWED")),
                8900L, createdAt, createdAt.plus(QuoteRepository.QUOTE_TTL));
    }
}