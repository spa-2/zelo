package com.server.zelo.domain.user.emailverification.repository;

import com.server.zelo.common.util.HashUtil;
import com.server.zelo.domain.user.emailverification.dto.CodeValidationResult;
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

    @Test
    void 코드가_일치하면_성공이고_코드와_실패_횟수가_삭제된다() {
        // given
        repository.saveCode(email, PURPOSE, "123456", Duration.ofMinutes(5));
        repository.validateCode(email, PURPOSE, "000000", MAX, Duration.ofMinutes(5)); // 실패 1회

        // when
        CodeValidationResult result = repository.validateCode(email, PURPOSE, "123456", MAX, Duration.ofMinutes(5));

        // then
        assertThat(result).isEqualTo(CodeValidationResult.SUCCESS);
        assertThat(redisTemplate.keys("EMAIL_CODE*:*" + hashUtil.hash(email))).isEmpty();
    }

    @Test
    void 같은_코드로_다시_확인하면_만료로_처리된다() {
        // given
        repository.saveCode(email, PURPOSE, "123456", Duration.ofMinutes(5));
        repository.validateCode(email, PURPOSE, "123456", MAX, Duration.ofMinutes(5));

        // when & then 1회용이라 재사용할 수 없다
        assertThat(repository.validateCode(email, PURPOSE, "123456", MAX, Duration.ofMinutes(5)))
                .isEqualTo(CodeValidationResult.EXPIRED);
    }

    @Test
    void 코드가_없으면_만료다() {
        // when & then
        assertThat(repository.validateCode(email, PURPOSE, "123456", MAX, Duration.ofMinutes(5)))
                .isEqualTo(CodeValidationResult.EXPIRED);
    }

    @Test
    void 코드가_틀리면_불일치이고_실패_횟수가_TTL과_함께_쌓인다() {
        // given
        repository.saveCode(email, PURPOSE, "123456", Duration.ofMinutes(5));

        // when
        CodeValidationResult result = repository.validateCode(email, PURPOSE, "000000", MAX, Duration.ofMinutes(5));

        // then
        assertThat(result).isEqualTo(CodeValidationResult.MISMATCH);
        String failedKey = redisTemplate.keys("EMAIL_CODE_FAILED:*" + hashUtil.hash(email)).iterator().next();
        assertThat(redisTemplate.opsForValue().get(failedKey)).isEqualTo("1");
        assertThat(redisTemplate.getExpire(failedKey)).isBetween(295L, 300L);
        // 코드는 아직 유효해서 이어서 맞출 수 있다
        assertThat(repository.validateCode(email, PURPOSE, "123456", MAX, Duration.ofMinutes(5)))
                .isEqualTo(CodeValidationResult.SUCCESS);
    }

    @Test
    void 실패가_한도에_도달하면_코드가_폐기되고_맞는_코드도_더는_통하지_않는다() {
        // given
        repository.saveCode(email, PURPOSE, "123456", Duration.ofMinutes(5));
        for (int i = 0; i < MAX - 1; i++) {
            assertThat(repository.validateCode(email, PURPOSE, "000000", MAX, Duration.ofMinutes(5)))
                    .isEqualTo(CodeValidationResult.MISMATCH);
        }

        // when 5번째 실패
        CodeValidationResult last = repository.validateCode(email, PURPOSE, "000000", MAX, Duration.ofMinutes(5));

        // then
        assertThat(last).isEqualTo(CodeValidationResult.TOO_MANY_FAILURES);
        assertThat(repository.validateCode(email, PURPOSE, "123456", MAX, Duration.ofMinutes(5)))
                .isEqualTo(CodeValidationResult.EXPIRED);
        assertThat(redisTemplate.keys("EMAIL_CODE*:*" + hashUtil.hash(email))).isEmpty();
    }

    @Test
    void 재발송된_코드는_이전_코드의_실패_횟수를_물려받지_않는다() {
        // given
        repository.saveCode(email, PURPOSE, "111111", Duration.ofMinutes(5));
        for (int i = 0; i < MAX - 1; i++) {
            repository.validateCode(email, PURPOSE, "000000", MAX, Duration.ofMinutes(5));
        }

        // when 재발송 후 새 코드를 처음 틀림
        repository.saveCode(email, PURPOSE, "222222", Duration.ofMinutes(5));
        CodeValidationResult result = repository.validateCode(email, PURPOSE, "000000", MAX, Duration.ofMinutes(5));

        // then 이전 실패가 이어졌다면 TOO_MANY_FAILURES가 나온다
        assertThat(result).isEqualTo(CodeValidationResult.MISMATCH);
        assertThat(repository.validateCode(email, PURPOSE, "222222", MAX, Duration.ofMinutes(5)))
                .isEqualTo(CodeValidationResult.SUCCESS);
    }

    @Test
    void 다른_이메일의_코드로는_통과할_수_없다() {
        // given
        repository.saveCode(email, PURPOSE, "123456", Duration.ofMinutes(5));

        // when & then
        assertThat(repository.validateCode("other-" + email, PURPOSE, "123456", MAX, Duration.ofMinutes(5)))
                .isEqualTo(CodeValidationResult.EXPIRED);
    }

    @Test
    void 티켓은_원문이_아닌_해시를_키로_저장하고_값에_목적과_이메일이_들어간다() {
        // given
        String ticket = "ticket-" + UUID.randomUUID();

        // when
        repository.saveTicket(ticket, PURPOSE, email, Duration.ofMinutes(30));

        // then
        String key = "EMAIL_TICKET:" + hashUtil.hash(ticket);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("SIGNUP:" + email);
        assertThat(redisTemplate.getExpire(key)).isBetween(1795L, 1800L);
        assertThat(key).doesNotContain(ticket);
        redisTemplate.delete(key);
    }

    private long sendCountTtl() {
        return redisTemplate.getExpire("EMAIL_SEND_COUNT:" + hashUtil.hash(email));
    }

    private String ipKey() {
        return "EMAIL_IP_COUNT:" + hashUtil.hash(ip);
    }
}
