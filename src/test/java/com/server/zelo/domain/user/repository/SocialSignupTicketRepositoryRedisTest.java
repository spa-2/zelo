package com.server.zelo.domain.user.repository;

import com.server.zelo.common.util.HashUtil;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// getAndDelete가 실제로 한 번만 소비되는지는 mock으로 확인할 수 없어서 로컬과 CI에 떠 있는 Redis(localhost:6379)에 직접 붙어서 확인한다
// Redis가 없으면 이 테스트만 건너뜀 (Redis 없이도 나머지 단위 테스트는 돌아가야 해서)
class SocialSignupTicketRepositoryRedisTest {

    private static final String PROFILE_JSON =
            "{\"provider\":\"GOOGLE\",\"providerId\":\"1234567890\",\"email\":\"jieun@gmail.com\",\"emailVerified\":true,\"name\":\"지은\"}";

    private static StringRedisTemplate redisTemplate;
    private static LettuceConnectionFactory connectionFactory;

    private String ticket;
    private SocialSignupTicketRepository repository;
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
        repository = new SocialSignupTicketRepository(hashUtil, redisTemplate);
        // 다른 테스트나 개발 데이터와 키가 겹치지 않게 매번 새로운 번호표를 씀
        ticket = "test-ticket-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(key(ticket));
    }

    @Test
    void 저장한_번호표를_소비하면_저장한_값을_돌려준다() {
        // given
        repository.save(ticket, PROFILE_JSON);

        // when
        var result = repository.consume(ticket);

        // then
        assertThat(result).contains(PROFILE_JSON);
    }

    @Test
    void 소비한_번호표는_다시_소비할_수_없다() {
        // given
        repository.save(ticket, PROFILE_JSON);
        repository.consume(ticket);

        // when
        var result = repository.consume(ticket);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void 소비하면_Redis에서_지워진다() {
        // given
        repository.save(ticket, PROFILE_JSON);

        // when
        repository.consume(ticket);

        // then
        assertThat(redisTemplate.hasKey(key(ticket))).isFalse();
    }

    @Test
    void 저장하지_않은_번호표는_비어_있다() {
        // when
        var result = repository.consume("never-saved-" + UUID.randomUUID());

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void 번호표_원문이_아닌_해시로_키를_만든다() {
        // when
        repository.save(ticket, PROFILE_JSON);

        // then
        // Redis가 유출돼도 번호표를 그대로 쓸 수 없어야 함
        assertThat(redisTemplate.hasKey(key(ticket))).isTrue();
        assertThat(redisTemplate.hasKey("social:signup:" + ticket)).isFalse();
    }

    @Test
    void 번호표에는_만료_시간이_걸린다() {
        // when
        repository.save(ticket, PROFILE_JSON);

        // then
        // 가입을 끝내지 않은 사람의 이메일과 이름이 Redis에 계속 남지 않아야 함. 시간 값이 바뀌어도 깨지지 않게 상한만 확인함
        Long ttl = redisTemplate.getExpire(key(ticket), TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(1L, Duration.ofMinutes(30).toMillis());
    }

    @Test
    void 같은_번호표로_동시에_소비해도_한_번만_성공한다() throws Exception {
        // given
        int threads = 20;
        repository.save(ticket, PROFILE_JSON);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        // when
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return repository.consume(ticket).isPresent();
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
        // 읽기와 삭제가 따로 이루어지면 여러 요청이 같은 번호표로 가입을 시도할 수 있음
        assertThat(successCount).isEqualTo(1);
    }

    private String key(String ticket) {
        return "social:signup:" + hashUtil.hash(ticket);
    }
}
