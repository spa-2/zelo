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
    private static final String SUPERSEDED_KEY_PREFIX = "refresh:superseded:";

    // TTL을 토큰 만료 시간과 맞춰야 만료된 토큰이 Redis에 남지 않음
    public RefreshTokenRepository(StringRedisTemplate stringRedisTemplate,
                                  @Value("${jwt.refresh-expiration}") long refreshExpiration) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.refreshTokenTtl = Duration.ofMillis(refreshExpiration);
    }

    // 유저당 하나만 두어서 다시 로그인하면 이전 리프레시 토큰은 덮어써짐
    public void save(Long userId, String refreshToken) {
        String redisKey = key(userId);
        String redisHash = hash(refreshToken);

        // 기존 토큰 조회, 새 토큰 저장, 만료 시간 설정을 명령 하나로 처리하여 동시 로그인 시 값이 꼬이거나 만료 시간 없는 키가 남는걸 방지
        // (getAndSet 후 expire를 따로 부르면 그 사이에 장애가 나면 만료 시간 없는 키가 남음)
        String previousToken = stringRedisTemplate.opsForValue().setGet(redisKey, redisHash, refreshTokenTtl);

        // 기존에 리프레시 토큰이 있을 경우에는 superseded를 붙여 별도로 관리한다. (새 기기로 로그인 시 기존 기기 로그인 만료를 위해)
        // 같은 값이면 현재 토큰이 superseded로 기록되어 나중에 탈취가 아닌 다른 기기 로그인으로 오판되므로 제외함
        if (previousToken != null && !previousToken.equals(redisHash)) {
            stringRedisTemplate.opsForValue().set(supersededKey(previousToken), previousToken, refreshTokenTtl);
        }
    }

    public void delete(Long userId) {
        stringRedisTemplate.delete(key(userId));
    }

    // 토큰 재발급은 로그인과 달리 superseded에 기록하지 않음 (나중에 재사용되면 다른 기기 로그인이 아니라 탈취로 판단해야 하기 때문)
    public void rotate(Long userId, String newRefreshToken) {
        stringRedisTemplate.opsForValue().set(key(userId), hash(newRefreshToken), refreshTokenTtl);
    }

    public boolean matches(Long userId, String refreshToken) {
        // 현재 저장된 리프레쉬 해쉬값을 가져오고
        String savedHash = stringRedisTemplate.opsForValue().get(key(userId));
        // redis에 존재하고 사용자가 보낸 리프레시위 해쉬값과 일치하면 참
        return savedHash != null && savedHash.equals(hash(refreshToken));
    }

    public boolean exists(Long userId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key(userId)));
    }

    // 다른 기기 로그인으로 낡아진 토큰인지 확인
    public boolean isSuperseded(String refreshToken) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(supersededKey(hash(refreshToken))));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    // supersededKey는 userId를 기준으로 저장하지 않고 해시를 기준으로 저장하여 탈취에 대한 오탐을 줄임
    private String supersededKey(String hashedRefreshToken) {
        return SUPERSEDED_KEY_PREFIX + hashedRefreshToken;
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
