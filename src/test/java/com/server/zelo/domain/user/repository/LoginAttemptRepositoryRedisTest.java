package com.server.zelo.domain.user.repository;

import com.server.zelo.common.util.HashUtil;
import com.server.zelo.domain.user.dto.login.AttemptResult;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Lua 스크립트의 증가·TTL 전환이 실제로 맞는지는 mock으로 확인할 수 없어서 로컬과 CI에 떠 있는 Redis(localhost:6379)에 직접 붙어서 확인한다
// Redis가 없으면 이 테스트만 건너뜀 (Redis 없이도 나머지 단위 테스트는 돌아가야 해서)
class LoginAttemptRepositoryRedisTest {

    private static final String IP = "203.0.113.7";
    private static final int MAX = 5;
    private static final Duration LOCK = Duration.ofMinutes(10);
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private static StringRedisTemplate redisTemplate;
    private static LettuceConnectionFactory connectionFactory;

    private String email;
    private LoginAttemptRepository repository;
    private final HashUtil hashUtil = new HashUtil("SHA-256");

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
        repository = new LoginAttemptRepository(hashUtil, redisTemplate);
        // 다른 테스트나 개발 데이터와 키가 겹치지 않게 매번 새로운 이메일을 씀
        email = "test-" + UUID.randomUUID() + "@example.com";
    }

    @AfterEach
    void cleanUp() {
        repository.deleteLoginAttempt(email, IP);
        repository.deleteLoginAttempt(email, "198.51.100.1");
        repository.deleteLoginAttempt("other-" + email, IP);
    }

    @Test
    void 첫_시도는_횟수_1이고_남은_시간은_윈도우_시간이다() {
        // when
        AttemptResult result = repository.increment(email, IP, MAX, LOCK, WINDOW);

        // then
        assertThat(result.count()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isBetween(WINDOW.toSeconds() - 5, WINDOW.toSeconds());
    }

    @Test
    void 시도할수록_횟수가_1씩_늘고_윈도우_시간은_유지된다() {
        // when
        AttemptResult last = null;
        for (int i = 0; i < MAX; i++) {
            last = repository.increment(email, IP, MAX, LOCK, WINDOW);
        }

        // then
        assertThat(last.count()).isEqualTo(MAX);
        assertThat(last.retryAfterSeconds()).isLessThanOrEqualTo(WINDOW.toSeconds());
    }

    @Test
    void 한도를_넘는_순간_남은_시간이_잠금_시간으로_바뀐다() {
        // given
        for (int i = 0; i < MAX; i++) {
            repository.increment(email, IP, MAX, LOCK, WINDOW);
        }

        // when
        AttemptResult exceeded = repository.increment(email, IP, MAX, LOCK, WINDOW);

        // then
        assertThat(exceeded.count()).isEqualTo(MAX + 1);
        assertThat(exceeded.retryAfterSeconds()).isBetween(LOCK.toSeconds() - 5, LOCK.toSeconds());
    }

    @Test
    void 잠긴_뒤_계속_시도해도_잠금_시간이_늘어나지_않는다() {
        // given
        for (int i = 0; i < MAX; i++) {
            repository.increment(email, IP, MAX, LOCK, WINDOW);
        }
        AttemptResult exceeded = repository.increment(email, IP, MAX, LOCK, WINDOW);

        // when
        AttemptResult again = repository.increment(email, IP, MAX, LOCK, WINDOW);

        // then
        // 잠긴 동안 시도마다 시간이 늘어나면 공격자가 계속 시도해서 정상 사용자의 잠금을 끝나지 않게 만들 수 있음
        assertThat(again.count()).isEqualTo(MAX + 2);
        assertThat(again.retryAfterSeconds()).isLessThanOrEqualTo(exceeded.retryAfterSeconds());
    }

    @Test
    void 삭제하면_처음부터_다시_센다() {
        // given
        repository.increment(email, IP, MAX, LOCK, WINDOW);
        repository.increment(email, IP, MAX, LOCK, WINDOW);

        // when
        repository.deleteLoginAttempt(email, IP);
        AttemptResult result = repository.increment(email, IP, MAX, LOCK, WINDOW);

        // then
        assertThat(result.count()).isEqualTo(1);
    }

    @Test
    void 이메일과_IP_조합마다_따로_센다() {
        // given
        repository.increment(email, IP, MAX, LOCK, WINDOW);
        repository.increment(email, IP, MAX, LOCK, WINDOW);

        // when
        AttemptResult otherIp = repository.increment(email, "198.51.100.1", MAX, LOCK, WINDOW);
        AttemptResult otherEmail = repository.increment("other-" + email, IP, MAX, LOCK, WINDOW);

        // then
        // 다른 곳에서 틀린 시도가 쌓였다고 내 계정이 잠기면 남의 이메일로 시도해서 정상 사용자를 막을 수 있음
        assertThat(otherIp.count()).isEqualTo(1);
        assertThat(otherEmail.count()).isEqualTo(1);
    }

    @Test
    void 동시에_시도해도_횟수가_겹치거나_빠지지_않는다() throws Exception {
        // given
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AttemptResult>> futures = new ArrayList<>();

        // when
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return repository.increment(email, IP, MAX, LOCK, WINDOW);
            }));
        }
        ready.await();
        start.countDown();

        Set<Integer> counts = new TreeSet<>();
        for (Future<AttemptResult> future : futures) {
            counts.add(future.get(5, TimeUnit.SECONDS).count());
        }
        executor.shutdown();

        // then
        // 읽고 쓰는 방식이었다면 같은 횟수를 여러 요청이 받아 5회를 넘겨도 통과하는 요청이 생김
        assertThat(counts).hasSize(threads);
        assertThat(counts).containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.rangeClosed(1, threads).boxed().toList());
    }
}
