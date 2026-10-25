package com.server.zelo.domain.order.repository;

import com.server.zelo.domain.order.model.OrderConfirmation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OrderConfirmationLookupRepositoryTest {

    private static final String ID = "confirmation-161";
    private static final String KEY = "order:confirmation:" + ID;

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> values;

    private OrderConfirmationRepository repository;

    @BeforeEach
    void setUp() {
        repository = new OrderConfirmationRepository(JsonMapper.builder().build(), redisTemplate);
    }

    @Test
    void 저장된_JSON을_구매_확인정보로_복원한다() {
        // given
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.get(KEY)).willReturn("{\"userId\":7,\"roomId\":10,\"quoteId\":\"QUOTE-161\"}");

        // when
        var result = repository.findById(ID);

        // then
        assertThat(result).contains(new OrderConfirmation(7L, 10L, "QUOTE-161"));
        then(values).should().get(KEY);
    }

    @Test
    void 확인정보가_없거나_만료됐으면_빈_결과를_반환한다() {
        // given
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.get(KEY)).willReturn(null);

        // when
        var result = repository.findById(ID);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void 확인정보_키를_접두사와_함께_삭제한다() {
        // given
        given(redisTemplate.delete(KEY)).willReturn(true);

        // when
        repository.deleteById(ID);

        // then
        then(redisTemplate).should().delete(KEY);
        then(redisTemplate).shouldHaveNoMoreInteractions();
    }

    @Test
    void Redis_조회_장애를_확인정보_없음으로_숨기지_않는다() {
        // given
        given(redisTemplate.opsForValue()).willReturn(values);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("테스트 조회 실패");
        given(values.get(KEY)).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> repository.findById(ID)).isSameAs(failure);
    }
}
