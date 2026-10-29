package com.server.zelo.domain.user.emailverification.repository;

import com.server.zelo.common.util.HashUtil;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Lua 스크립트와 SET NX의 동작은 mock으로 확인할 수 없어서 localhost:6379의 Redis에 직접 붙어서 확인한다
// Redis가 없으면 이 테스트만 건너뜀
class EmailVerificationRepositoryRedisTest {

    private static final EmailVerificationPurpose PURPOSE = EmailVerificationPurpose.SIGNUP;
    private static final int MAX = 5;
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Duration BLOCK = Duration.ofMinutes(10);

    private static StringRedisTemplate redisTemplate;
    private static LettuceConnectionFactory connectionFactory;

    private String email;
    private String ip;
    private EmailVerificationRepository repository;
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
        repository = new EmailVerificationRepository(hashUtil, redisTemplate);
        // 다른 테스트나 개발 데이터와 키가 겹치지 않게 매번 새로운 이메일을 씀
        email = "test-" + UUID.randomUUID() + "@example.com";
        // ip 카운터는 이메일과 달리 값이 고정이면 다른 실행과 겹치므로 임의의 문서용 대역 주소를 쓴다
        ip = "203.0.113." + ThreadLocalRandom.current().nextInt(1, 255);
    }

    @AfterEach
    void cleanUp() {
        Set<String> keys = redisTemplate.keys("EMAIL_*:*" + hashUtil.hash(email) + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        redisTemplate.delete(ipKey());
    }

    @Test
    void 쿨다운은_처음에만_선점되고_이후에는_실패한다() {
        // when & then
        assertThat(repository.acquireCooldown(email, PURPOSE, Duration.ofSeconds(60))).isTrue();
        assertThat(repository.acquireCooldown(email, PURPOSE, Duration.ofSeconds(60))).isFalse();
    }

    @Test
    void 쿨다운은_이메일별로_독립이다() {
        // given
        repository.acquireCooldown(email, PURPOSE, Duration.ofSeconds(60));

        // when & then
        assertThat(repository.acquireCooldown("other-" + email, PURPOSE, Duration.ofSeconds(60))).isTrue();
        redisTemplate.delete(redisTemplate.keys("EMAIL_COOLDOWN:*" + hashUtil.hash("other-" + email)));
    }

    @Test
    void 쿨다운에는_TTL이_설정된다() {
        // given
        repository.acquireCooldown(email, PURPOSE, Duration.ofSeconds(60));

        // when
        Set<String> keys = redisTemplate.keys("EMAIL_COOLDOWN:*" + hashUtil.hash(email));
        Long ttl = redisTemplate.getExpire(keys.iterator().next());

        // then
        assertThat(ttl).isBetween(55L, 60L);
    }

    @Test
    void 발송할수록_횟수가_1씩_늘고_TTL은_윈도우_시간이다() {
        // when
        long first = repository.increaseSendCount(email, MAX, WINDOW, BLOCK);
        long second = repository.increaseSendCount(email, MAX, WINDOW, BLOCK);

        // then
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(sendCountTtl()).isBetween(WINDOW.toSeconds() - 5, WINDOW.toSeconds());
    }

    @Test
    void 한도를_넘는_순간_TTL이_차단_시간으로_바뀐다() {
        // given
        for (int i = 0; i < MAX; i++) {
            repository.increaseSendCount(email, MAX, WINDOW, BLOCK);
        }

        // when
        long exceeded = repository.increaseSendCount(email, MAX, WINDOW, BLOCK);

        // then
        assertThat(exceeded).isEqualTo(MAX + 1);
        assertThat(sendCountTtl()).isBetween(BLOCK.toSeconds() - 5, BLOCK.toSeconds());
    }

    @Test
    void 이메일_횟수는_IP와_무관하게_이메일_기준으로_센다() {
        // given
        repository.increaseSendCount(email, MAX, WINDOW, BLOCK);

        // when
        long other = repository.increaseSendCount("other-" + email, MAX, WINDOW, BLOCK);

        // then 다른 이메일은 따로 센다
        assertThat(other).isEqualTo(1);
        redisTemplate.delete(redisTemplate.keys("EMAIL_SEND_COUNT:" + hashUtil.hash("other-" + email)));
    }

    @Test
    void IP_횟수는_처음_1이고_TTL은_윈도우_시간이다() {
        // when
        long first = repository.increaseIpCount(ip, MAX, WINDOW, BLOCK);

        // then
        assertThat(first).isEqualTo(1);
        assertThat(redisTemplate.getExpire(ipKey())).isBetween(WINDOW.toSeconds() - 5, WINDOW.toSeconds());
    }

    @Test
    void IP_횟수가_한도를_넘는_순간_TTL이_차단_시간으로_바뀐다() {
        // given
        for (int i = 0; i < MAX; i++) {
            repository.increaseIpCount(ip, MAX, WINDOW, BLOCK);
        }

        // when
        long exceeded = repository.increaseIpCount(ip, MAX, WINDOW, BLOCK);

        // then
        assertThat(exceeded).isEqualTo(MAX + 1);
        assertThat(redisTemplate.getExpire(ipKey())).isBetween(BLOCK.toSeconds() - 5, BLOCK.toSeconds());
    }

    @Test
    void IP_횟수는_원문_IP_없이_저장되고_이메일_횟수와_키가_분리된다() {
        // given
        repository.increaseIpCount(ip, MAX, WINDOW, BLOCK);
        repository.increaseSendCount(email, MAX, WINDOW, BLOCK);

        // when & then
        assertThat(ipKey()).doesNotContain(ip);
        assertThat(redisTemplate.opsForValue().get(ipKey())).isEqualTo("1");
        // 같은 윈도우라도 이메일 카운터는 ip 카운터에 영향을 주지 않는다
        assertThat(repository.increaseIpCount(ip, MAX, WINDOW, BLOCK)).isEqualTo(2);
        assertThat(repository.increaseSendCount(email, MAX, WINDOW, BLOCK)).isEqualTo(2);
    }

    @Test
    void IP가_다르면_따로_센다() {
        // given
        repository.increaseIpCount(ip, MAX, WINDOW, BLOCK);

        // when & then
        assertThat(repository.increaseIpCount("198.51.100.1", MAX, WINDOW, BLOCK)).isEqualTo(1);
        redisTemplate.delete("EMAIL_IP_COUNT:" + hashUtil.hash("198.51.100.1"));
    }

    @Test
    void 코드는_원문이_아닌_해시로_저장되고_TTL이_설정된다() {
        // when
        repository.saveCode(email, PURPOSE, "123456", Duration.ofMinutes(5));

        // then
        Set<String> keys = redisTemplate.keys("EMAIL_CODE:*" + hashUtil.hash(email));
        assertThat(keys).hasSize(1);
        String key = keys.iterator().next();
        assertThat(key).doesNotContain(email);
        String stored = redisTemplate.opsForValue().get(key);
        assertThat(stored).isNotEqualTo("123456").isEqualTo(hashUtil.hash(email + ":123456"));
        assertThat(redisTemplate.getExpire(key)).isBetween(295L, 300L);
    }

    @Test
    void 같은_코드라도_이메일이_다르면_저장되는_값이_다르다() {
        // given
        String other = "other-" + email;

        // when
        repository.saveCode(email, PURPOSE, "123456", Duration.ofMinutes(5));
        repository.saveCode(other, PURPOSE, "123456", Duration.ofMinutes(5));

        // then
        String mine = redisTemplate.opsForValue().get(redisTemplate.keys("EMAIL_CODE:*" + hashUtil.hash(email)).iterator().next());
        String theirs = redisTemplate.opsForValue().get(redisTemplate.keys("EMAIL_CODE:*" + hashUtil.hash(other)).iterator().next());
        assertThat(mine).isNotEqualTo(theirs);
        redisTemplate.delete(redisTemplate.keys("EMAIL_CODE:*" + hashUtil.hash(other)));
    }

    @Test
    void 코드를_다시_저장하면_새_코드로_덮어쓴다() {
        // given
        repository.saveCode(email, PURPOSE, "111111", Duration.ofMinutes(5));

        // when
        repository.saveCode(email, PURPOSE, "222222", Duration.ofMinutes(5));

        // then
        String key = redisTemplate.keys("EMAIL_CODE:*" + hashUtil.hash(email)).iterator().next();
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(hashUtil.hash(email + ":222222"));
    }

    private long sendCountTtl() {
        return redisTemplate.getExpire("EMAIL_SEND_COUNT:" + hashUtil.hash(email));
    }

    private String ipKey() {
        return "EMAIL_IP_COUNT:" + hashUtil.hash(ip);
    }
}
