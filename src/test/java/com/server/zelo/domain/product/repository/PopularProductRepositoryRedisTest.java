package com.server.zelo.domain.product.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

// 여러 날짜 키를 합치고 TTL이 붙는지는 mock으로 확인할 수 없어서 로컬과 CI의 Redis(localhost:6379)에 직접 붙음
// 개발 데이터랑 안 겹치게 2099년 날짜 키만 쓰고 끝나면 지움
class PopularProductRepositoryRedisTest {

    private static final LocalDate TODAY = LocalDate.of(2099, 1, 10);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private PopularProductRepository popularProductRepository;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        boolean available;
        try {
            available = "PONG".equals(connectionFactory.getConnection().ping());
        } catch (Exception e) {
            available = false;
        }
        assumeTrue(available, "localhost:6379에 Redis가 없어서 건너뜀");
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        popularProductRepository = new PopularProductRepository(redisTemplate);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(redisTemplate.keys("product:sales:2099*"));
        redisTemplate.delete("product:sales:top");
    }

    @Test
    void 최근_7일_판매_수량을_합쳐서_많은_순으로_준다() {
        // given
        popularProductRepository.increase(1L, 2, TODAY);
        popularProductRepository.increase(1L, 3, TODAY.minusDays(6));
        popularProductRepository.increase(2L, 4, TODAY.minusDays(1));
        popularProductRepository.increase(3L, 9, TODAY.minusDays(7));

        // when
        List<TypedTuple<String>> result = popularProductRepository.findTop(TODAY, 10);

        // then
        assertThat(result).extracting(TypedTuple::getValue, TypedTuple::getScore)
                .containsExactly(tuple("1", 5.0), tuple("2", 4.0));
    }

    @Test
    void 취소로_합이_0_이하가_된_상품은_빠진다() {
        // given
        popularProductRepository.increase(1L, 2, TODAY.minusDays(2));
        popularProductRepository.increase(1L, -2, TODAY);
        popularProductRepository.increase(2L, -1, TODAY);
        popularProductRepository.increase(3L, 1, TODAY);

        // when
        List<TypedTuple<String>> result = popularProductRepository.findTop(TODAY, 10);

        // then
        assertThat(result).extracting(TypedTuple::getValue).containsExactly("3");
    }

    @Test
    void 개수를_지키고_합친_결과는_1분_동안_다시_쓴다() {
        // given
        popularProductRepository.increase(1L, 3, TODAY);
        popularProductRepository.increase(2L, 2, TODAY);
        popularProductRepository.increase(3L, 1, TODAY);
        popularProductRepository.findTop(TODAY, 2);
        popularProductRepository.increase(3L, 10, TODAY);

        // when
        List<TypedTuple<String>> result = popularProductRepository.findTop(TODAY, 2);

        // then
        assertThat(result).extracting(TypedTuple::getValue).containsExactly("1", "2");
        assertThat(redisTemplate.getExpire("product:sales:top")).isBetween(1L, 60L);
    }

    @Test
    void 날짜_키에_TTL을_붙인다() {
        // when
        popularProductRepository.increase(1L, 1, TODAY);

        // then
        assertThat(redisTemplate.getExpire("product:sales:20990110")).isBetween(7 * 86400L, 8 * 86400L);
    }
}