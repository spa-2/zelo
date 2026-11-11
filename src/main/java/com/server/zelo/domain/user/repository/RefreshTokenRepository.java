package com.server.zelo.domain.user.repository;

import com.server.zelo.common.util.HashUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

@Repository
public class RefreshTokenRepository {

    private final HashUtil hashUtil;
    private final Duration refreshTokenTtl;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String REFRESH_KEY_PREFIX = "refresh:";
    private static final String ROTATED_KEY_PREFIX = "refresh:rotated:";
    private static final String SUPERSEDED_KEY_PREFIX = "refresh:superseded:";

    // 길면 탈취된 직전 토큰을 탈취로 보지 않는 시간이 길어지고, 너무 짧으면 동시 요청이 세션 삭제로 이어져서 짧게 둠
    private static final Duration ROTATED_MARKER_TTL = Duration.ofSeconds(10);

    // KEYS[1]: 현재 토큰 키, KEYS[2]: 직전 토큰의 rotated 키
    // ARGV[1]: 제시한 토큰 해시, ARGV[2]: 새 토큰 해시, ARGV[3]: 토큰 TTL(ms), ARGV[4]: rotated 표식 TTL(ms)
    private static final RedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            -- 저장된 현재 토큰 해시가 제시한 토큰의 해시와 같을 때만 아래를 실행함
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                -- 교체한다: 현재 토큰을 새 토큰 해시로 덮어씀
                redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
                -- rotated로 기록한다: 방금 교체된 직전 토큰을 짧은 시간만 표시함
                redis.call('SET', KEYS[2], '1', 'PX', ARGV[4])
                return 1
            end
            -- 다르거나 저장된 값이 없으면 아무것도 바꾸지 않음
            return 0
            """, Long.class);

    // KEYS[1]: 현재 토큰 키
    // ARGV[1]: 새 토큰 해시, ARGV[2]: 토큰 TTL(ms), ARGV[3]: superseded 키 접두사
    // 이전 토큰 값으로 superseded 키를 스크립트 안에서 만들어서 클러스터에서는 키 슬롯 설계가 따로 필요함 (지금은 단일 Redis)
    private static final RedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
            -- 덮어쓰기 전의 현재 토큰 해시를 읽어 둠 (없으면 false)
            local previous = redis.call('GET', KEYS[1])
            -- 교체한다: 현재 토큰을 새 토큰 해시로 덮어씀
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            -- 이전 토큰이 있고 새 토큰과 다를 때만 superseded로 기록한다 (다른 기기 로그인으로 밀려난 토큰)
            -- 같은 값이면 현재 토큰이 superseded로 기록되어 나중에 탈취가 다른 기기 로그인으로 오판되므로 제외함
            if previous and previous ~= ARGV[1] then
                redis.call('SET', ARGV[3] .. previous, previous, 'PX', ARGV[2])
            end
            return 1
            """, Long.class);

    // TTL을 토큰 만료 시간과 맞춰야 만료된 토큰이 Redis에 남지 않음
    public RefreshTokenRepository(StringRedisTemplate stringRedisTemplate,
                                  @Value("${jwt.refresh-expiration}") long refreshExpiration,
                                  HashUtil hashUtil
                                  ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.refreshTokenTtl = Duration.ofMillis(refreshExpiration);
        this.hashUtil = hashUtil;
    }

    // 유저당 하나만 두어서 다시 로그인하면 이전 리프레시 토큰은 덮어써짐
    public void save(Long userId, String refreshToken) {
        // 현재 토큰 교체와 superseded 기록을 스크립트(SAVE_SCRIPT) 하나로 처리함
        // 두 명령으로 나누면 그 사이에 끼어든 재발급이 표식 없이 새 토큰만 보고 탈취로 판단해 방금 로그인한 세션을 삭제할 수 있음
        // 만료 시간도 같은 명령 안에서 걸어서 장애가 나도 만료 시간 없는 키가 남지 않음
        stringRedisTemplate.execute(
                SAVE_SCRIPT,
                List.of(key(userId)),
                hashUtil.hash(refreshToken),
                String.valueOf(refreshTokenTtl.toMillis()),
                SUPERSEDED_KEY_PREFIX);
    }

    public void delete(Long userId) {
        stringRedisTemplate.delete(key(userId));
    }

    // 저장된 해시가 제시한 토큰의 해시와 같을 때만 새 토큰으로 교체하고, 교체된 토큰은 짧은 시간 rotated로 기록함
    // 확인과 교체 사이에 로그인, 로그아웃, 다른 재발급이 끼어들 수 없도록 Redis 안에서 한 번에 처리함
    // 로그인과 달리 superseded에는 기록하지 않음 (나중에 재사용되면 다른 기기 로그인이 아니라 탈취로 판단해야 하기 때문)
    public boolean rotateIfMatches(Long userId, String receivedRefreshToken, String newRefreshToken) {
        String presentedHash = hashUtil.hash(receivedRefreshToken);

        // 일치 확인, 교체, rotated 기록은 모두 이 스크립트(ROTATE_SCRIPT) 안에서 한 번에 처리됨
        // 반환값 1은 교체되고 rotated로 기록된 것이고, 0은 아무것도 바뀌지 않은 것
        Long result = stringRedisTemplate.execute(
                ROTATE_SCRIPT,
                List.of(key(userId), rotatedKey(presentedHash)),
                presentedHash,
                hashUtil.hash(newRefreshToken),
                String.valueOf(refreshTokenTtl.toMillis()),
                String.valueOf(ROTATED_MARKER_TTL.toMillis()));

        return Long.valueOf(1L).equals(result);
    }

    public boolean exists(Long userId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key(userId)));
    }

    // 다른 기기 로그인으로 낡아진 토큰인지 확인
    public boolean isSuperseded(String refreshToken) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(supersededKey(hashUtil.hash(refreshToken))));
    }

    // 방금 재발급으로 교체된 토큰인지 확인 (동시 요청이나 응답 유실 후 재시도로 들어온 경우)
    public boolean isRotated(String refreshToken) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(rotatedKey(hashUtil.hash(refreshToken))));
    }

    private String key(Long userId) {
        return REFRESH_KEY_PREFIX + userId;
    }

    private String rotatedKey(String hashedRefreshToken) {
        return ROTATED_KEY_PREFIX + hashedRefreshToken;
    }

    // supersededKey는 userId를 기준으로 저장하지 않고 해시를 기준으로 저장하여 탈취에 대한 오탐을 줄임
    private String supersededKey(String hashedRefreshToken) {
        return SUPERSEDED_KEY_PREFIX + hashedRefreshToken;
    }

}
