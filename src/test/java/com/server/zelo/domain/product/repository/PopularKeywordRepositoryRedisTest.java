package com.server.zelo.domain.product.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

// 여러 시간대 키를 합치고 TTL이 붙는지는 mock으로 확인할 수 없어서 로컬과 CI의 Redis(localhost:6379)에 직접 붙음
// 개발 데이터랑 안 겹치게 2099년 시간대 키만 쓰고 끝나면 지움
class PopularKeywordRepositoryRedisTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 2, 10, 30);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private PopularKeywordRepository popularKeywordRepository;

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
        popularKeywordRepository = new PopularKeywordRepository(redisTemplate);
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(redisTemplate.keys("search:keyword:2099*"));
    }

    @Test
    void 최근_24시간_검색어를_합쳐서_많은_순으로_준다() {
        // given
        popularKeywordRepository.increase("김치", NOW);
        popularKeywordRepository.increase("김치", NOW.minusHours(5));
        popularKeywordRepository.increase("두부", NOW.minusHours(23));
        popularKeywordRepository.increase("계란", NOW.minusHours(24));

        // when
        List<TypedTuple<String>> result = popularKeywordRepository.findTop(NOW, 10);

        // then
        assertThat(result).extracting(TypedTuple::getValue, TypedTuple::getScore)
                .containsExactly(tuple("김치", 2.0), tuple("두부", 1.0));
    }

    @Test
    void 횟수가_같으면_검색어_순으로_자르고_개수를_지킨다() {
        // given
        popularKeywordRepository.increase("두부", NOW);
        popularKeywordRepository.increase("계란", NOW);
        popularKeywordRepository.increase("김치", NOW);

        // when
        List<TypedTuple<String>> result = popularKeywordRepository.findTop(NOW, 2);

        // then
        assertThat(result).extracting(TypedTuple::getValue).containsExactly("계란", "김치");
    }

    @Test
    void 시간대_키에_TTL을_붙인다() {
        // when
        popularKeywordRepository.increase("김치", NOW);

        // then
        assertThat(redisTemplate.getExpire("search:keyword:2099010210")).isBetween(24 * 3600L, 25 * 3600L);
    }
}