package com.server.zelo.domain.order.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.types.Expiration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class OrderConfirmationRepositoryTest {

    private static final String CONFIRMATION_ID = "23b36845-19aa-45d9-87c0-0b4bb8c30390";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Captor
    private ArgumentCaptor<String> jsonCaptor;

    @Captor
    private ArgumentCaptor<Expiration> expirationCaptor;

    private JsonMapper jsonMapper;
    private OrderConfirmationRepository orderConfirmationRepository;

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder().build();
        orderConfirmationRepository = new OrderConfirmationRepository(jsonMapper, stringRedisTemplate);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 123_456_789})
    void 확인_정보와_견적의_절대_만료시각을_한_번의_SET으로_저장한다(int nanos) {
        // given
        LocalDateTime expiresAt = LocalDateTime.of(2030, 1, 2, 12, 10, 0, nanos);
        long expectedDeadline = expiresAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        orderConfirmationRepository.save(CONFIRMATION_ID, 7L, 10L, "QUOTE-114", expiresAt);

        // then
        then(valueOperations).should().set(
                eq("order:confirmation:" + CONFIRMATION_ID), jsonCaptor.capture(), expirationCaptor.capture());
        JsonNode json = jsonMapper.readTree(jsonCaptor.getValue());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get("userId").asLong()).isEqualTo(7L);
        assertThat(json.get("roomId").asLong()).isEqualTo(10L);
        assertThat(json.get("quoteId").stringValue()).isEqualTo("QUOTE-114");
        assertThat(expirationCaptor.getValue().isUnixTimestamp()).isTrue();
        assertThat(expirationCaptor.getValue().getTimeUnit()).isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(expirationCaptor.getValue().getExpirationTimeInMilliseconds()).isEqualTo(expectedDeadline);
        then(stringRedisTemplate).should().opsForValue();
        then(stringRedisTemplate).shouldHaveNoMoreInteractions();
        then(valueOperations).shouldHaveNoMoreInteractions();
    }

    @Test
    void Redis_저장에_실패하면_예외를_숨기지_않는다() {
        // given
        LocalDateTime expiresAt = LocalDateTime.of(2030, 1, 2, 12, 10);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("테스트 연결 실패");
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        willThrow(failure).given(valueOperations).set(anyString(), anyString(), any(Expiration.class));

        // when
        // then
        assertThatThrownBy(() -> orderConfirmationRepository.save(
                CONFIRMATION_ID, 7L, 10L, "QUOTE-114", expiresAt))
                .isSameAs(failure);
        then(valueOperations).should().set(anyString(), anyString(), any(Expiration.class));
        then(valueOperations).shouldHaveNoMoreInteractions();
    }
}
