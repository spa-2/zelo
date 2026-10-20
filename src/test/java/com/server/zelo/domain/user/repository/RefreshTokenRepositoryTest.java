package com.server.zelo.domain.user.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

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
    }

    @Test
    void 토큰_원문_대신_해시를_만료시간과_함께_저장한다() {
        // given
        String refreshToken = "refreshToken";
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        refreshTokenRepository.save(1L, refreshToken);

        // then
        then(valueOperations).should().setGet(
                eq("refresh:1"),
                // 원문이 그대로 저장되지 않고 SHA-256 hex(64자)로 저장되는지 확인
                argThat(saved -> !saved.equals(refreshToken) && saved.matches("^[0-9a-f]{64}$")),
                eq(Duration.ofMillis(REFRESH_EXPIRATION)));
    }

    @Test
    void 기존_토큰이_없으면_superseded를_저장하지_않는다() {
        // given
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setGet(eq("refresh:1"), anyString(), any(Duration.class))).willReturn(null);

        // when
        refreshTokenRepository.save(1L, "refreshToken");

        // then
        then(valueOperations).should(never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 기존_토큰이_있으면_해시_기준_superseded로_리프레시_토큰_수명만큼_저장한다() {
        // given
        String previousHash = "a".repeat(64);
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setGet(eq("refresh:1"), anyString(), any(Duration.class))).willReturn(previousHash);

        // when
        refreshTokenRepository.save(1L, "newRefreshToken");

        // then
        // userId가 아니라 기존 토큰의 해시로 키를 만들어야 여러 번 덮어써도 이전 기기의 기록이 남음
        then(valueOperations).should().set(
                "refresh:superseded:" + previousHash, previousHash, Duration.ofMillis(REFRESH_EXPIRATION));
    }

    @Test
    void 기존_토큰과_같은_토큰이면_superseded로_저장하지_않는다() {
        // given
        String refreshToken = "refreshToken";
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        // 같은 토큰으로 두 번 저장하면 기존 값이 새 값의 해시와 같음
        given(valueOperations.setGet(eq("refresh:1"), anyString(), any(Duration.class)))
                .willAnswer(invocation -> invocation.getArgument(1));

        // when
        refreshTokenRepository.save(1L, refreshToken);

        // then
        then(valueOperations).should(never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 유저의_리프레시_토큰_키를_삭제한다() {
        // when
        refreshTokenRepository.delete(1L);

        // then
        then(stringRedisTemplate).should().delete("refresh:1");
    }

    @Test
    void 교체할_때는_해시를_저장하고_superseded는_기록하지_않는다() {
        // given
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);

        // when
        refreshTokenRepository.rotate(1L, "newRefreshToken");

        // then
        then(valueOperations).should().set(
                "refresh:1", sha256("newRefreshToken"), Duration.ofMillis(REFRESH_EXPIRATION));
        then(valueOperations).should(never()).set(
                startsWith("refresh:superseded:"), anyString(), any(Duration.class));
    }

    @Test
    void 저장된_해시와_같은_토큰이면_일치한다() {
        // given
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("refresh:1")).willReturn(sha256("refreshToken"));

        // when & then
        assertThat(refreshTokenRepository.matches(1L, "refreshToken")).isTrue();
    }

    @Test
    void 저장된_해시와_다른_토큰이면_일치하지_않는다() {
        // given
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("refresh:1")).willReturn(sha256("otherToken"));

        // when & then
        assertThat(refreshTokenRepository.matches(1L, "refreshToken")).isFalse();
    }

    @Test
    void 저장된_토큰이_없으면_일치하지_않는다() {
        // given
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("refresh:1")).willReturn(null);

        // when & then
        assertThat(refreshTokenRepository.matches(1L, "refreshToken")).isFalse();
    }

    @Test
    void 유저의_리프레시_토큰_키가_있으면_존재한다() {
        // given
        given(stringRedisTemplate.hasKey("refresh:1")).willReturn(true);

        // when & then
        assertThat(refreshTokenRepository.exists(1L)).isTrue();
    }

    @Test
    void 유저의_리프레시_토큰_키가_없으면_존재하지_않는다() {
        // given
        given(stringRedisTemplate.hasKey("refresh:1")).willReturn(false);

        // when & then
        assertThat(refreshTokenRepository.exists(1L)).isFalse();
    }

    @Test
    void superseded에_기록된_토큰이면_true를_반환한다() {
        // given
        given(stringRedisTemplate.hasKey("refresh:superseded:" + sha256("oldToken"))).willReturn(true);

        // when & then
        assertThat(refreshTokenRepository.isSuperseded("oldToken")).isTrue();
    }

    @Test
    void superseded에_없는_토큰이면_false를_반환한다() {
        // given
        given(stringRedisTemplate.hasKey("refresh:superseded:" + sha256("oldToken"))).willReturn(false);

        // when & then
        assertThat(refreshTokenRepository.isSuperseded("oldToken")).isFalse();
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
