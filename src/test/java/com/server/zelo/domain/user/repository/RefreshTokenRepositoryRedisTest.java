package com.server.zelo.domain.user.repository;

import com.server.zelo.common.util.HashUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Lua 스크립트와 SET ... GET은 mock으로 검증할 수 없어서 로컬과 CI에 떠 있는 Redis(localhost:6379)에 직접 붙어서 확인한다
// Redis가 없으면 이 테스트만 건너뜀 (Redis 없이도 나머지 단위 테스트는 돌아가야 해서)
class RefreshTokenRepositoryRedisTest {

    private Long userId;
    private static final long REFRESH_EXPIRATION = 60_000L;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RefreshTokenRepository refreshTokenRepository;
    private final List<String> createdKeys = new ArrayList<>();

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
        refreshTokenRepository = new RefreshTokenRepository(redisTemplate, REFRESH_EXPIRATION, new HashUtil("SHA-256"));
        // 개발 데이터나 다른 테스트와 키가 겹치지 않게 아주 큰 임의의 userId를 씀
        userId = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, Long.MAX_VALUE);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete("refresh:" + userId);
        redisTemplate.delete(createdKeys);
    }

    @Test
    void 현재_토큰과_같으면_교체하고_직전_토큰을_rotated로_기록한다() {
        // given
        refreshTokenRepository.save(userId, "A1");
        trackRotated("A1");

        // when
        boolean rotated = refreshTokenRepository.rotateIfMatches(userId, "A1", "A2");

        // then
        assertThat(rotated).isTrue();
        assertThat(redisTemplate.opsForValue().get("refresh:" + userId)).isEqualTo(sha256("A2"));
        assertThat(refreshTokenRepository.isRotated("A1")).isTrue();
        // 교체된 토큰은 로그인으로 낡아진 것이 아니므로 superseded에는 없어야 함
        assertThat(refreshTokenRepository.isSuperseded("A1")).isFalse();
    }

    @Test
    void 교체한_토큰과_rotated_표식에는_만료_시간이_걸린다() {
        // given
        refreshTokenRepository.save(userId, "A1");
        trackRotated("A1");

        // when
        refreshTokenRepository.rotateIfMatches(userId, "A1", "A2");

        // then
        Long tokenTtl = redisTemplate.getExpire("refresh:" + userId, TimeUnit.MILLISECONDS);
        Long markerTtl = redisTemplate.getExpire("refresh:rotated:" + sha256("A1"), TimeUnit.MILLISECONDS);
        assertThat(tokenTtl).isBetween(1L, REFRESH_EXPIRATION);
        // 표식은 짧게만 유지돼야 함 (길면 탈취된 직전 토큰을 탈취로 보지 않는 시간이 길어짐)
        assertThat(markerTtl).isBetween(1L, 10_000L);
    }

    @Test
    void 현재_토큰과_다르면_교체하지_않고_아무것도_기록하지_않는다() {
        // given
        refreshTokenRepository.save(userId, "A1");
        trackRotated("other");

        // when
        boolean rotated = refreshTokenRepository.rotateIfMatches(userId, "other", "A2");

        // then
        assertThat(rotated).isFalse();
        assertThat(redisTemplate.opsForValue().get("refresh:" + userId)).isEqualTo(sha256("A1"));
        assertThat(refreshTokenRepository.isRotated("other")).isFalse();
    }

    @Test
    void 로그아웃한_뒤에는_교체해도_세션이_되살아나지_않는다() {
        // given
        refreshTokenRepository.save(userId, "A1");
        trackRotated("A1");
        refreshTokenRepository.delete(userId);

        // when
        boolean rotated = refreshTokenRepository.rotateIfMatches(userId, "A1", "A2");

        // then
        assertThat(rotated).isFalse();
        assertThat(refreshTokenRepository.exists(userId)).isFalse();
        assertThat(refreshTokenRepository.isRotated("A1")).isFalse();
    }

    @Test
    void 다른_기기_로그인이_먼저_끼어들면_교체는_실패하고_새_기기의_세션이_유지된다() {
        // given
        // 기기 A의 토큰 A1이 현재 토큰인 상태에서 기기 B가 로그인해 B1이 저장됨
        refreshTokenRepository.save(userId, "A1");
        refreshTokenRepository.save(userId, "B1");
        trackSuperseded("A1");
        trackRotated("A1");

        // when
        // 기기 A의 재발급이 뒤늦게 실행됨
        boolean rotated = refreshTokenRepository.rotateIfMatches(userId, "A1", "A2");

        // then
        assertThat(rotated).isFalse();
        assertThat(redisTemplate.opsForValue().get("refresh:" + userId)).isEqualTo(sha256("B1"));
        // 기기 A는 탈취가 아니라 다른 기기 로그인으로 분류되어야 함
        assertThat(refreshTokenRepository.isSuperseded("A1")).isTrue();
        assertThat(refreshTokenRepository.isRotated("A1")).isFalse();
    }

    @Test
    void 교체_뒤_로그인하면_교체된_토큰이_superseded로_기록된다() {
        // given
        refreshTokenRepository.save(userId, "A1");
        trackRotated("A1");
        trackSuperseded("A2");
        refreshTokenRepository.rotateIfMatches(userId, "A1", "A2");

        // when
        refreshTokenRepository.save(userId, "B1");

        // then
        assertThat(redisTemplate.opsForValue().get("refresh:" + userId)).isEqualTo(sha256("B1"));
        assertThat(refreshTokenRepository.isSuperseded("A2")).isTrue();
    }

    @Test
    void 같은_토큰으로_동시에_교체해도_하나만_성공한다() throws Exception {
        // given
        int threads = 20;
        refreshTokenRepository.save(userId, "A1");
        trackRotated("A1");
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        // when
        for (int i = 0; i < threads; i++) {
            String newToken = "A2-" + i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return refreshTokenRepository.rotateIfMatches(userId, "A1", newToken);
            }));
        }
        ready.await();
        start.countDown();

        int successCount = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                successCount++;
            }
        }
        executor.shutdown();

        // then
        // 확인과 교체가 한 번에 처리되지 않으면 여러 요청이 동시에 통과해 서로 다른 새 토큰을 받게 됨
        assertThat(successCount).isEqualTo(1);
        assertThat(refreshTokenRepository.isRotated("A1")).isTrue();
    }

    // 현재 토큰과 superseded 표식이 따로 쓰이면, 새 토큰이 보이는데 표식은 아직 없는 순간이 생겨서 재발급이 탈취로 오판함
    // 경쟁을 확률적으로만 잡는 테스트라서 반복해서 확인함 (현재 토큰이 새 값으로 보이면 표식도 반드시 보여야 함)
    @Test
    void 새_토큰이_현재_토큰으로_보이면_이전_토큰의_superseded_표식도_반드시_보인다() throws Exception {
        // given
        int iterations = 300;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Long> violatedUserIds = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            long id = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, Long.MAX_VALUE);
            String currentKey = "refresh:" + id;
            String supersededKey = "refresh:superseded:" + sha256("A1");
            createdKeys.add(currentKey);
            createdKeys.add(supersededKey);
            refreshTokenRepository.save(id, "A1");
            redisTemplate.delete(supersededKey);

            CountDownLatch start = new CountDownLatch(1);
            // when
            Future<?> login = executor.submit(() -> {
                start.await();
                refreshTokenRepository.save(id, "B1");
                return null;
            });
            Future<Boolean> observer = executor.submit(() -> {
                start.await();
                // 새 토큰이 보이기 전까지는 확인할 것이 없음. 보인 뒤에는 표식이 있어야 함
                while (!sha256("B1").equals(redisTemplate.opsForValue().get(currentKey))) {
                    Thread.onSpinWait();
                }
                return Boolean.TRUE.equals(redisTemplate.hasKey(supersededKey));
            });
            start.countDown();
            login.get(10, TimeUnit.SECONDS);

            // then
            if (!observer.get(10, TimeUnit.SECONDS)) {
                violatedUserIds.add(id);
            }
            redisTemplate.delete(currentKey);
            redisTemplate.delete(supersededKey);
        }
        executor.shutdown();

        assertThat(violatedUserIds).isEmpty();
    }

    @Test
    void 처음_저장하면_superseded를_기록하지_않고_만료_시간이_걸린다() {
        // given
        trackSuperseded("A1");

        // when
        refreshTokenRepository.save(userId, "A1");

        // then
        assertThat(refreshTokenRepository.isSuperseded("A1")).isFalse();
        Long ttl = redisTemplate.getExpire("refresh:" + userId, TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(1L, REFRESH_EXPIRATION);
    }

    @Test
    void 같은_토큰을_두_번_저장해도_superseded로_기록하지_않는다() {
        // given
        trackSuperseded("A1");
        refreshTokenRepository.save(userId, "A1");

        // when
        refreshTokenRepository.save(userId, "A1");

        // then
        assertThat(refreshTokenRepository.isSuperseded("A1")).isFalse();
    }

    private void trackRotated(String token) {
        createdKeys.add("refresh:rotated:" + sha256(token));
    }

    private void trackSuperseded(String token) {
        createdKeys.add("refresh:superseded:" + sha256(token));
    }

    // 구현의 hash와 같은 값을 만드는 게 아니라 SHA-256의 정의대로 직접 계산해서 구현이 맞는지 확인함
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
