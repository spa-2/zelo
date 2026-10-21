package com.server.zelo.common.auth;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final long ACCESS_TIME = 600_000L;
    private static final long REFRESH_TIME = 1_209_600_000L;

    private JwtUtil jwtUtil;
    private SecretKey testKey;

    @BeforeEach
    void setUp() {
        // 실제 jwt secret은 쓰지 않고 테스트마다 랜덤 키를 만듦
        testKey = Jwts.SIG.HS256.key().build();

        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", Base64.getEncoder().encodeToString(testKey.getEncoded()));
        ReflectionTestUtils.setField(jwtUtil, "accessTokenTime", ACCESS_TIME);
        ReflectionTestUtils.setField(jwtUtil, "refreshTokenTime", REFRESH_TIME);
        jwtUtil.init();
    }

    @Test
    void Bearer_접두사가_있으면_순수_토큰을_반환한다() {
        // given
        String header = "Bearer abc.def.ghi";

        // when
        String token = jwtUtil.substringToken(header);

        // then
        assertThat(token).isEqualTo("abc.def.ghi");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void 헤더_값이_비어있으면_예외가_발생한다(String header) {
        // when & then
        assertThatThrownBy(() -> jwtUtil.substringToken(header))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void Bearer_접두사가_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> jwtUtil.substringToken("abc.def.ghi"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void 정상_토큰은_검증에_성공한다() {
        // given
        String token = jwtUtil.createAccessToken(1L);

        // when
        boolean result = jwtUtil.validateToken(token);

        // then
        assertThat(result).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void 토큰이_비어있으면_검증에_실패한다(String token) {
        // when
        boolean result = jwtUtil.validateToken(token);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void 만료된_토큰은_검증에_실패한다() {
        // given
        ReflectionTestUtils.setField(jwtUtil, "accessTokenTime", -1_000L);
        String token = jwtUtil.createAccessToken(1L);

        // when
        boolean result = jwtUtil.validateToken(token);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void 페이로드가_변조된_토큰은_검증에_실패한다() {
        // given
        String[] userOne = jwtUtil.createAccessToken(1L).split("\\.");
        String[] userTwo = jwtUtil.createAccessToken(2L).split("\\.");
        // 다른 유저의 페이로드에 기존 서명을 붙여서 서명이 안 맞게 만듦
        String forged = userOne[0] + "." + userTwo[1] + "." + userOne[2];

        // when
        boolean result = jwtUtil.validateToken(forged);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void 다른_키로_서명한_토큰은_검증에_실패한다() {
        // given
        String token = Jwts.builder()
                .subject("1")
                .expiration(new Date(System.currentTimeMillis() + ACCESS_TIME))
                .signWith(Jwts.SIG.HS256.key().build())
                .compact();

        // when
        boolean result = jwtUtil.validateToken(token);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void 액세스_토큰은_액세스_타입으로_판별된다() {
        // given
        String token = jwtUtil.createAccessToken(1L);

        // when & then
        assertThat(jwtUtil.isAccessToken(token)).isTrue();
        assertThat(jwtUtil.isRefreshToken(token)).isFalse();
    }

    @Test
    void 리프레시_토큰은_리프레시_타입으로_판별된다() {
        // given
        String token = jwtUtil.createRefreshToken(1L);

        // when & then
        assertThat(jwtUtil.isRefreshToken(token)).isTrue();
        assertThat(jwtUtil.isAccessToken(token)).isFalse();
    }

    @Test
    void 타입_claim이_없는_토큰은_액세스_토큰이_아니다() {
        // given
        String token = Jwts.builder()
                .subject("1")
                .expiration(new Date(System.currentTimeMillis() + ACCESS_TIME))
                .signWith(testKey)
                .compact();

        // when & then
        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.isAccessToken(token)).isFalse();
        assertThat(jwtUtil.isRefreshToken(token)).isFalse();
    }

    @Test
    void 토큰에서_유저_id를_꺼낼_수_있다() {
        // given
        String token = jwtUtil.createAccessToken(7L);

        // when
        Long id = jwtUtil.getUserId(token);

        // then
        assertThat(id).isEqualTo(7L);
    }

    @Test
    void 리프레시_토큰의_만료시간이_액세스_토큰보다_길다() {
        // given
        String access = jwtUtil.createAccessToken(1L);
        String refresh = jwtUtil.createRefreshToken(1L);

        // when
        Date accessExp = parse(access);
        Date refreshExp = parse(refresh);

        // then
        assertThat(refreshExp).isAfter(accessExp);
    }

    @Test
    void 토큰에는_UUID_형식의_jti가_들어있다() {
        // given
        String token = jwtUtil.createRefreshToken(1L);

        // when
        String jti = parseClaims(token).getId();

        // then
        assertThat(jti).isNotNull();
        assertThat(UUID.fromString(jti).toString()).isEqualTo(jti);
    }

    @Test
    void 같은_유저로_연속_발급해도_리프레시_토큰이_서로_다르다() {
        // given
        // 발급 시각이 초 단위라 jti가 없으면 같은 초에 만든 토큰이 완전히 같아짐
        String first = jwtUtil.createRefreshToken(1L);
        String second = jwtUtil.createRefreshToken(1L);

        // when & then
        assertThat(first).isNotEqualTo(second);
        assertThat(parseClaims(first).getId()).isNotEqualTo(parseClaims(second).getId());
    }

    @Test
    void 같은_유저로_연속_발급해도_액세스_토큰이_서로_다르다() {
        // given
        String first = jwtUtil.createAccessToken(1L);
        String second = jwtUtil.createAccessToken(1L);

        // when & then
        assertThat(first).isNotEqualTo(second);
    }

    private Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(testKey).build()
                .parseSignedClaims(token).getPayload();
    }

    private Date parse(String token) {
        return Jwts.parser().verifyWith(testKey).build()
                .parseSignedClaims(token).getPayload().getExpiration();
    }
}
