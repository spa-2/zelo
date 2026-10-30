package com.server.zelo.domain.chat.repository;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChatRateLimitRepository {

    private static final String KEY_PREFIX = "chat:rate:";

    // INCR와 EXPIRE를 따로 보내면 그 사이에 실패했을 때 만료 없는 키가 남아 그 사용자가 계속 막히므로 한 스크립트로 묶는다
    private static final RedisScript<Long> COUNT_SCRIPT = new DefaultRedisScript<>("""
            -- 횟수 증가
            local count = redis.call('INCR', KEYS[1])

            -- 첫 요청에만 윈도우 TTL 설정 (키가 사라지면 다시 1부터 센다)
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end

            -- 현재 횟수 반환
            return count
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public long increaseCount(Long userId, Duration window) {
        return stringRedisTemplate.execute(
                COUNT_SCRIPT,
                List.of(key(userId)),
                String.valueOf(window.toSeconds()) // argv 1
        );
    }

    // 다시 보낼 수 있을 때까지 남은 초. 그사이 키가 사라졌어도 Retry-After는 1초 이상으로 준다
    public long getRemainingSeconds(Long userId) {
        Long seconds = stringRedisTemplate.getExpire(key(userId));
        return seconds == null || seconds < 1 ? 1 : seconds;
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
