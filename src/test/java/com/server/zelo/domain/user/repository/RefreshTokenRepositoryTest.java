package com.server.zelo.domain.user.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
    @SuppressWarnings("unchecked")
    void 교체는_스크립트로_현재_키와_rotated_키_해시_TTL을_넘겨_실행한다() {
        // given
        given(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willReturn(1L);

        // when
        boolean result = refreshTokenRepository.rotateIfMatches(1L, "oldToken", "newToken");

        // then
        assertThat(result).isTrue();
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        // 제시한 토큰 해시, 새 토큰 해시, 토큰 TTL(ms), rotated 표식 TTL(ms)
        then(stringRedisTemplate).should().execute(any(RedisScript.class), keys.capture(),
                eq(sha256("oldToken")), eq(sha256("newToken")), eq(String.valueOf(REFRESH_EXPIRATION)), eq("10000"));
        // 현재 키, 직전 토큰의 rotated 키(해시 기준)
        assertThat(keys.getValue()).containsExactly("refresh:1", "refresh:rotated:" + sha256("oldToken"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 스크립트가_0을_반환하면_교체되지_않은_것이다() {
        // given
        given(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willReturn(0L);

        // when & then
        assertThat(refreshTokenRepository.rotateIfMatches(1L, "oldToken", "newToken")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 스크립트_결과가_null이면_교체되지_않은_것이다() {
        // given
        given(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willReturn(null);

        // when & then
        assertThat(refreshTokenRepository.rotateIfMatches(1L, "oldToken", "newToken")).isFalse();
    }

    @Test
    void rotated에_기록된_토큰이면_true를_반환한다() {
        // given
        given(stringRedisTemplate.hasKey("refresh:rotated:" + sha256("oldToken"))).willReturn(true);

        // when & then
        assertThat(refreshTokenRepository.isRotated("oldToken")).isTrue();
    }

    @Test
    void rotated에_없는_토큰이면_false를_반환한다() {
        // given
        given(stringRedisTemplate.hasKey("refresh:rotated:" + sha256("oldToken"))).willReturn(false);

        // when & then
        assertThat(refreshTokenRepository.isRotated("oldToken")).isFalse();
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
