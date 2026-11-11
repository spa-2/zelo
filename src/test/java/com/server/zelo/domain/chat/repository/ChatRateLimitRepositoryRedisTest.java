package com.server.zelo.domain.chat.repository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Lua 스크립트의 증가·TTL 설정이 실제로 맞는지는 mock으로 확인할 수 없어서 로컬과 CI에 떠 있는 Redis(localhost:6379)에 직접 붙어서 확인한다
// Redis가 없으면 이 테스트만 건너뜀
class ChatRateLimitRepositoryRedisTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static StringRedisTemplate redisTemplate;
    private static LettuceConnectionFactory connectionFactory;

    private Long userId;
    private ChatRateLimitRepository repository;

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
        repository = new ChatRateLimitRepository(redisTemplate);
        // 다른 테스트나 개발 데이터와 키가 겹치지 않게 매번 다른 사용자 ID를 씀
        userId = ThreadLocalRandom.current().nextLong(9_000_000_000L, 9_999_999_999L);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(List.of("chat:rate:" + userId, "chat:rate:" + (userId + 1)));
    }

    @Test
    void 첫_요청은_횟수_1이고_남은_시간은_윈도우_시간이다() {
        // when
        long count = repository.increaseCount(userId, WINDOW);

        // then
        assertThat(count).isEqualTo(1);
        assertThat(repository.getRemainingSeconds(userId)).isBetween(WINDOW.toSeconds() - 5, WINDOW.toSeconds());
    }

    @Test
    void 요청할수록_횟수가_1씩_늘고_남은_시간은_늘어나지_않는다() {
        // given
        repository.increaseCount(userId, WINDOW);
        long first = repository.getRemainingSeconds(userId);

        // when
        long count = 0;
        for (int i = 0; i < 11; i++) {
            count = repository.increaseCount(userId, WINDOW);
        }

        // then
        // 요청마다 시간이 다시 늘어나면 계속 보내는 동안 제한이 끝나지 않음
        assertThat(count).isEqualTo(12);
        assertThat(repository.getRemainingSeconds(userId)).isLessThanOrEqualTo(first);
    }

    @Test
    void 키가_없으면_남은_시간을_1초로_준다() {
        // when & then
        assertThat(repository.getRemainingSeconds(userId)).isEqualTo(1);
    }

    @Test
    void 사용자마다_따로_센다() {
        // given
        repository.increaseCount(userId, WINDOW);
        repository.increaseCount(userId, WINDOW);

        // when
        long other = repository.increaseCount(userId + 1, WINDOW);

        // then
        assertThat(other).isEqualTo(1);
    }

    @Test
    void 동시에_요청해도_횟수가_겹치거나_빠지지_않는다() throws Exception {
        // given
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();

        // when
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return repository.increaseCount(userId, WINDOW);
            }));
        }
        ready.await();
        start.countDown();

        Set<Long> counts = new TreeSet<>();
        for (Future<Long> future : futures) {
            counts.add(future.get(5, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // then
        // 읽고 쓰는 방식이었다면 같은 횟수를 여러 요청이 받아 10회를 넘겨도 통과하는 요청이 생김
        assertThat(counts).containsExactlyElementsOf(LongStream.rangeClosed(1, threads).boxed().toList());
    }
}
