package com.server.zelo.domain.user.social;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.social.config.GoogleOAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleSocialLoginClientTest {

    private static final String TOKEN_URI = "https://google.test/token";
    private static final String USER_INFO_URI = "https://google.test/userinfo";
    private static final String REDIRECT_URI = "http://localhost:8080/auth/google/callback";

    private MockRestServiceServer server;
    private GoogleSocialLoginClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        GoogleOAuthProperties properties = new GoogleOAuthProperties(
                "client-id", "client-secret", TOKEN_URI, USER_INFO_URI,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        client = new GoogleSocialLoginClient(builder.build(), properties);
    }

    private void expectTokenSuccess() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"google-access\"}", MediaType.APPLICATION_JSON));
    }

    private void expectUserInfo(String body) {
        server.expect(requestTo(USER_INFO_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer google-access"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void assertErrorCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    @Test
    void 제공자는_GOOGLE이다() {
        assertThat(client.getProvider()).isEqualTo(SocialProvider.GOOGLE);
    }

    @Test
    void 구글_응답을_SocialProfile로_변환한다() {
        // given
        expectTokenSuccess();
        expectUserInfo("""
                {"sub":"1234","email":"jieun@gmail.com","email_verified":true,"name":"지은"}
                """);

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(profile.providerId()).isEqualTo("1234");
        assertThat(profile.email()).isEqualTo("jieun@gmail.com");
        assertThat(profile.emailVerified()).isTrue();
        assertThat(profile.name()).isEqualTo("지은");
        server.verify();
    }

    @Test
    void 토큰_교환_요청에_필요한_값을_폼으로_보낸다() {
        // given
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                // 테스트에는 charset=UTF-8이 들어가지 않음
                .andExpect(header("Content-Type", "application/x-www-form-urlencoded"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("code=auth-code"),
                        org.hamcrest.Matchers.containsString("client_id=client-id"),
                        org.hamcrest.Matchers.containsString("client_secret=client-secret"),
                        org.hamcrest.Matchers.containsString("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fauth%2Fgoogle%2Fcallback"),
                        org.hamcrest.Matchers.containsString("grant_type=authorization_code"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("code_verifier")))))
                .andRespond(withSuccess("{\"access_token\":\"google-access\"}", MediaType.APPLICATION_JSON));
        expectUserInfo("{\"sub\":\"1234\"}");

        // when
        client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        server.verify();
    }

    @Test
    void codeVerifier가_있으면_code_verifier를_함께_보낸다() {
        // given
        server.expect(requestTo(TOKEN_URI))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code_verifier=verifier-value")))
                .andRespond(withSuccess("{\"access_token\":\"google-access\"}", MediaType.APPLICATION_JSON));
        expectUserInfo("{\"sub\":\"1234\"}");

        // when
        client.getProfile("auth-code", REDIRECT_URI, "verifier-value");

        // then
        server.verify();
    }

    @Test
    void 이메일_검증_여부_이메일_이름이_없어도_변환한다() {
        // given
        expectTokenSuccess();
        expectUserInfo("{\"sub\":\"1234\"}");

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.emailVerified()).isFalse();
        assertThat(profile.email()).isNull();
        assertThat(profile.name()).isNull();
    }

    @Test
    void email_verified가_false이면_false로_변환한다() {
        // given
        expectTokenSuccess();
        expectUserInfo("{\"sub\":\"1234\",\"email\":\"a@gmail.com\",\"email_verified\":false}");

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.emailVerified()).isFalse();
    }

    @Test
    void 모르는_필드가_있어도_변환한다() {
        // given
        expectTokenSuccess();
        expectUserInfo("{\"sub\":\"1234\",\"picture\":\"https://x/y.png\",\"locale\":\"ko\"}");

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.providerId()).isEqualTo("1234");
    }

    @Test
    void 토큰_교환이_4xx이면_SOCIAL_AUTH_FAILED이고_사용자_정보를_조회하지_않는다() {
        // given
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\"}"));
        server.expect(never(), requestTo(USER_INFO_URI));

        // when & then
        assertErrorCode(() -> client.getProfile("used-code", REDIRECT_URI, null), ErrorCode.SOCIAL_AUTH_FAILED);
        server.verify();
    }

    @Test
    void 토큰_교환이_5xx이면_SOCIAL_PROVIDER_UNAVAILABLE이다() {
        // given
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        // when & then
        assertErrorCode(() -> client.getProfile("auth-code", REDIRECT_URI, null), ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
    }

    @Test
    void 토큰_교환이_타임아웃이면_SOCIAL_PROVIDER_UNAVAILABLE이다() {
        // given
        server.expect(requestTo(TOKEN_URI)).andRespond(withException(new SocketTimeoutException()));

        // when & then
        assertErrorCode(() -> client.getProfile("auth-code", REDIRECT_URI, null), ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
    }

    @Test
    void 토큰_응답에_access_token이_없으면_SOCIAL_AUTH_FAILED이다() {
        // given
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // when & then
        assertErrorCode(() -> client.getProfile("auth-code", REDIRECT_URI, null), ErrorCode.SOCIAL_AUTH_FAILED);
    }

    @Test
    void 토큰_응답이_비어_있으면_SOCIAL_AUTH_FAILED이다() {
        // given
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess());

        // when & then
        assertErrorCode(() -> client.getProfile("auth-code", REDIRECT_URI, null), ErrorCode.SOCIAL_AUTH_FAILED);
    }

    @Test
    void 사용자_정보_조회가_401이면_SOCIAL_AUTH_FAILED이다() {
        // given
        expectTokenSuccess();
        server.expect(requestTo(USER_INFO_URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        // when & then
        assertErrorCode(() -> client.getProfile("auth-code", REDIRECT_URI, null), ErrorCode.SOCIAL_AUTH_FAILED);
    }

    @Test
    void 사용자_정보_조회가_5xx이면_SOCIAL_PROVIDER_UNAVAILABLE이다() {
        // given
        expectTokenSuccess();
        server.expect(requestTo(USER_INFO_URI)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // when & then
        assertErrorCode(() -> client.getProfile("auth-code", REDIRECT_URI, null), ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
    }

    @Test
    void 사용자_정보_조회가_타임아웃이면_SOCIAL_PROVIDER_UNAVAILABLE이다() {
        // given
        expectTokenSuccess();
        server.expect(requestTo(USER_INFO_URI)).andRespond(withException(new SocketTimeoutException()));

        // when & then
        assertErrorCode(() -> client.getProfile("auth-code", REDIRECT_URI, null), ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
    }

    @Test
    void 사용자_정보에_sub가_없으면_SOCIAL_AUTH_FAILED이다() {
        // given
        expectTokenSuccess();
        expectUserInfo("{\"email\":\"a@gmail.com\"}");

        // when & then
        assertErrorCode(() -> client.getProfile("auth-code", REDIRECT_URI, null), ErrorCode.SOCIAL_AUTH_FAILED);
    }

    @Test
    void 사용자_정보_응답이_비어_있으면_SOCIAL_AUTH_FAILED이다() {
        // given
        expectTokenSuccess();
        server.expect(requestTo(USER_INFO_URI)).andRespond(withSuccess());

        // when & then
        assertErrorCode(() -> client.getProfile("auth-code", REDIRECT_URI, null), ErrorCode.SOCIAL_AUTH_FAILED);
    }
}