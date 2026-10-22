package com.server.zelo.common.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenCookieFactoryTest {

    private static final long REFRESH_EXPIRATION = 1_209_600_000L;

    private final RefreshTokenCookieFactory factory = new RefreshTokenCookieFactory(REFRESH_EXPIRATION);

    @Test
    void 리프레시_토큰_쿠키는_토큰_값을_담는다() {
        // when
        ResponseCookie cookie = factory.create("refreshToken");

        // then
        assertThat(cookie.getName()).isEqualTo("refreshToken");
        assertThat(cookie.getValue()).isEqualTo("refreshToken");
    }

    @Test
    void 리프레시_토큰_쿠키는_HttpOnly다() {
        assertThat(attributes(factory.create("refreshToken"))).contains("HttpOnly");
    }

    @Test
    void 리프레시_토큰_쿠키는_Secure다() {
        assertThat(attributes(factory.create("refreshToken"))).contains("Secure");
    }

    @Test
    void 리프레시_토큰_쿠키의_Path는_reissue다() {
        assertThat(attributes(factory.create("refreshToken"))).contains("Path=/api/auth/reissue");
    }

    @Test
    void 리프레시_토큰_쿠키는_리프레시_토큰_만료_시간만큼_유지된다() {
        assertThat(attributes(factory.create("refreshToken"))).contains("Max-Age=1209600");
    }

    @Test
    void 만료_쿠키는_값이_비어있고_바로_만료된다() {
        // when
        ResponseCookie cookie = factory.expire();

        // then
        assertThat(cookie.getName()).isEqualTo("refreshToken");
        assertThat(cookie.getValue()).isEmpty();
        assertThat(attributes(cookie)).contains("Max-Age=0");
    }

    // 속성이 하나라도 다르면 브라우저가 다른 쿠키로 취급해서 만료시켜도 지워지지 않음
    // (예: SameSite를 create에만 추가하면 이 테스트가 실패함)
    @Test
    void 만료_쿠키는_발급_쿠키와_이름_Path_보안_속성이_같다() {
        // when
        List<String> created = fixedAttributes(factory.create("refreshToken"));
        List<String> expired = fixedAttributes(factory.expire());

        // then
        assertThat(expired).containsExactlyInAnyOrderElementsOf(created);
    }

    private List<String> attributes(ResponseCookie cookie) {
        return Arrays.stream(cookie.toString().split(";")).map(String::trim).toList();
    }

    // 값, 만료 시간처럼 일부러 다른 속성은 빼고 비교함
    private List<String> fixedAttributes(ResponseCookie cookie) {
        return attributes(cookie).stream()
                .filter(attribute -> !attribute.startsWith("refreshToken="))
                .filter(attribute -> !attribute.startsWith("Max-Age="))
                .filter(attribute -> !attribute.startsWith("Expires="))
                .toList();
    }
}
