package com.server.zelo.domain.user.repository;

import com.server.zelo.common.util.HashUtil;
import com.server.zelo.domain.user.dto.login.AttemptResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class LoginAttemptRepository {


    private static final String LOGIN_ATTEMPT_KEY_PREFIX = "LOGIN_ATTEMPT:";
    private static final RedisScript<List> LOGIN_ATTEMPT_SCRIPT = new DefaultRedisScript<>("""
            -- 로그인 시도 횟수 증가
            local count = redis.call('INCR', KEYS[1])

            -- 첫 시도, 일반 윈도우 TTL 설정
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            -- 첫 시도 이후
            elseif count == tonumber(ARGV[2]) + 1 then
                -- 제한 초과가 된 그 순간에만 잠금 TTL로 변경
                redis.call('EXPIRE', KEYS[1], ARGV[3])
            end

            -- 현재 시도 횟수와 남은 TTL 반환
            return {count, redis.call('TTL', KEYS[1])}
            """, List.class);

    private final HashUtil hashUtil;
    private final StringRedisTemplate stringRedisTemplate;

    public AttemptResult increment(String email, String remoteAddr, int maxAttempts, Duration lock, Duration window) {
        List result = stringRedisTemplate.execute(
                LOGIN_ATTEMPT_SCRIPT,
                List.of(getKey(email, remoteAddr)), // key
                String.valueOf(window.toSeconds()), // argv 1
                String.valueOf(maxAttempts), // argv 2
                String.valueOf(lock.toSeconds()) // argv 3
        );

        return AttemptResult.of( (Number)result.getFirst(), (Number)result.get(1) );
    }

    public void deleteLoginAttempt(String email, String remoteAddr) {
        stringRedisTemplate.delete(getKey(email, remoteAddr));
    }

    private String getKey(String email, String remoteAddr) {
        return LOGIN_ATTEMPT_KEY_PREFIX + hashUtil.hash(email + ":" + remoteAddr);
    }
}
