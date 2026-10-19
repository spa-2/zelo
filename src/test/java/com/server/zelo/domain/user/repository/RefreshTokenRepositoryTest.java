package com.server.zelo.domain.user.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RefreshTokenRepositoryTest {

    private static final long REFRESH_EXPIRATION = 1209600000L;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void setUp() {
        refreshTokenRepository = new RefreshTokenRepository(stringRedisTemplate, REFRESH_EXPIRATION);
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
    }

    @Test
    void 토큰_원문_대신_해시를_만료시간과_함께_저장한다() {
        // given
        String refreshToken = "refreshToken";

        // when
        refreshTokenRepository.save(1L, refreshToken);

        // then
        then(valueOperations).should().set(
                eq("refresh:1"),
                // 원문이 그대로 저장되지 않고 SHA-256 hex(64자)로 저장되는지 확인
                argThat(saved -> !saved.equals(refreshToken) && saved.matches("^[0-9a-f]{64}$")),
                eq(Duration.ofMillis(REFRESH_EXPIRATION)));
    }
}
