package com.server.zelo.domain.user.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Repository
public class RefreshTokenRepository {

    private final Duration refreshTokenTtl;
    private static final String KEY_PREFIX = "refresh:";
    private final StringRedisTemplate stringRedisTemplate;

    // TTL을 토큰 만료 시간과 맞춰야 만료된 토큰이 Redis에 남지 않음
    public RefreshTokenRepository(StringRedisTemplate stringRedisTemplate,
                                  @Value("${jwt.refresh-expiration}") long refreshExpiration) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.refreshTokenTtl = Duration.ofMillis(refreshExpiration);
    }

    // 유저당 하나만 두어서 다시 로그인하면 이전 리프레시 토큰은 덮어써짐
    public void save(Long userId, String refreshToken) {
        stringRedisTemplate.opsForValue().set(key(userId), hash(refreshToken), refreshTokenTtl);
    }

    public void delete(Long userId) {
        stringRedisTemplate.delete(key(userId));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    // 토큰 원문은 저장하지 않고 해시 처리하여서 redis가 유출 되어도 토큰 탈취가 되지 않음
    private String hash(String refreshToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 지원해야 하는 알고리즘이라 여기 올 수 없지만 언체크라서 예외를 잡긴 해야됨
            throw new IllegalStateException(e);
        }
    }
}
