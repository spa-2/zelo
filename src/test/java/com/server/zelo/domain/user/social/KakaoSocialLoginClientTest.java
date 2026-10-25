package com.server.zelo.domain.user.social;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.social.config.KakaoOAuthProperties;
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
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoSocialLoginClientTest {

    private static final String TOKEN_URI = "https://kakao.test/oauth/token";
    private static final String USER_INFO_URI = "https://kakao.test/v2/user/me";
    private static final String REDIRECT_URI = "http://localhost:8080/auth/kakao/callback";

    private static final String FULL_USER_INFO = """
            {
              "id": 5153191939,
              "connected_at": "2026-10-25T07:28:26Z",
              "properties": {"nickname": "옛닉네임"},
              "kakao_account": {
                "profile_nickname_needs_agreement": false,
                "profile": {"nickname": "신원열", "is_default_nickname": false},
                "has_email": true,
                "email_needs_agreement": false,
                "is_email_valid": true,
                "is_email_verified": true,
                "email": "jieun@kakao.test"
              }
            }
            """;

    private MockRestServiceServer server;
    private KakaoSocialLoginClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        KakaoOAuthProperties properties = new KakaoOAuthProperties(
                "client-id", "client-secret", TOKEN_URI, USER_INFO_URI,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        client = new KakaoSocialLoginClient(builder.build(), properties);
    }

    private void expectTokenSuccess() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"kakao-access\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
    }

    private void expectUserInfo(String body) {
        server.expect(requestTo(USER_INFO_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer kakao-access"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void assertErrorCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    @Test
    void 제공자는_KAKAO이다() {
        assertThat(client.getProvider()).isEqualTo(SocialProvider.KAKAO);
    }

    @Test
    void 카카오_응답을_SocialProfile로_변환한다() {
        // given
        expectTokenSuccess();
        expectUserInfo(FULL_USER_INFO);

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.provider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(profile.providerId()).isEqualTo("5153191939");
        assertThat(profile.email()).isEqualTo("jieun@kakao.test");
        assertThat(profile.emailVerified()).isTrue();
        // 최상위 properties.nickname이 아니라 kakao_account.profile.nickname을 사용
        assertThat(profile.name()).isEqualTo("신원열");
        server.verify();
    }

    @Test
    void 토큰_교환_요청에_필요한_값을_폼으로_보낸다() {
        // given
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/x-www-form-urlencoded"))
                .andExpect(content().string(allOf(
                        containsString("code=auth-code"),
                        containsString("client_id=client-id"),
                        containsString("client_secret=client-secret"),
                        containsString("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fauth%2Fkakao%2Fcallback"),
                        containsString("grant_type=authorization_code"))))
                .andRespond(withSuccess("{\"access_token\":\"kakao-access\"}", MediaType.APPLICATION_JSON));
        expectUserInfo(FULL_USER_INFO);

        // when
        client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        server.verify();
    }

    @Test
    void codeVerifier를_받아도_카카오에는_보내지_않는다() {
        // given
        server.expect(requestTo(TOKEN_URI))
                .andExpect(content().string(not(containsString("code_verifier"))))
                .andRespond(withSuccess("{\"access_token\":\"kakao-access\"}", MediaType.APPLICATION_JSON));
        expectUserInfo(FULL_USER_INFO);

        // when
        client.getProfile("auth-code", REDIRECT_URI, "verifier-value");

        // then
        server.verify();
    }

    @Test
    void 카카오_응답에_모르는_필드가_있어도_변환한다() {
        // given
        expectTokenSuccess();
        expectUserInfo("""
                {"id": 1, "unknown": "x", "kakao_account": {"email": "a@kakao.test", "is_email_valid": true,
                 "is_email_verified": true, "age_range": "20~29", "profile": {"nickname": "닉", "thumbnail_image_url": "u"}}}
                """);

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.providerId()).isEqualTo("1");
        assertThat(profile.name()).isEqualTo("닉");
    }

    @Test
    void 이메일이_유효하지만_인증되지_않았으면_emailVerified는_false이다() {
        // given
        expectTokenSuccess();
        expectUserInfo("""
                {"id": 1, "kakao_account": {"email": "a@kakao.test", "is_email_valid": true, "is_email_verified": false}}
                """);

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.emailVerified()).isFalse();
        assertThat(profile.email()).isEqualTo("a@kakao.test");
    }

    @Test
    void 인증되었지만_유효하지_않은_이메일이면_emailVerified는_false이다() {
        // given
        expectTokenSuccess();
        expectUserInfo("""
                {"id": 1, "kakao_account": {"email": "a@kakao.test", "is_email_valid": false, "is_email_verified": true}}
                """);

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.emailVerified()).isFalse();
    }

    @Test
    void 검증_필드가_없으면_emailVerified는_false이다() {
        // given
        expectTokenSuccess();
        expectUserInfo("{\"id\": 1, \"kakao_account\": {\"email\": \"a@kakao.test\"}}");

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.emailVerified()).isFalse();
    }

    @Test
    void 이메일_동의가_없어도_이메일은_null이고_예외없이_변환한다() {
        // given
        expectTokenSuccess();
        expectUserInfo("""
                {"id": 1, "kakao_account": {"email_needs_agreement": true, "has_email": true,
                 "profile": {"nickname": "닉"}}}
                """);

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.email()).isNull();
        assertThat(profile.emailVerified()).isFalse();
        assertThat(profile.name()).isEqualTo("닉");
    }

    @Test
    void 프로필_동의가_없으면_이름은_null이다() {
        // given
        expectTokenSuccess();
        expectUserInfo("""
                {"id": 1, "kakao_account": {"email": "a@kakao.test", "is_email_valid": true, "is_email_verified": true}}
                """);

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.name()).isNull();
    }

    @Test
    void kakao_account가_통째로_없어도_예외없이_변환한다() {
        // given
        expectTokenSuccess();
        expectUserInfo("{\"id\": 1}");

        // when
        SocialProfile profile = client.getProfile("auth-code", REDIRECT_URI, null);

        // then
        assertThat(profile.providerId()).isEqualTo("1");
        assertThat(profile.email()).isNull();
        assertThat(profile.emailVerified()).isFalse();
        assertThat(profile.name()).isNull();
    }

    @Test
    void 토큰_교환이_4xx이면_SOCIAL_AUTH_FAILED이고_사용자_정보를_조회하지_않는다() {
        // given
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"authorization code not found\"}"));
        server.expect(never(), requestTo(USER_INFO_URI));

        // when & then
        assertErrorCode(() -> client.getProfile("used-code", REDIRECT_URI, null), ErrorCode.SOCIAL_AUTH_FAILED);
        server.verify();
    }

    @Test
    void 클라이언트_인증이_틀리면_SOCIAL_AUTH_FAILED이다() {
        // given
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\"}"));

        // when & then
        assertErrorCode(() -> client.getProfile("auth-code", REDIRECT_URI, null), ErrorCode.SOCIAL_AUTH_FAILED);
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
    void 사용자_정보에_id가_없으면_SOCIAL_AUTH_FAILED이다() {
        // given
        expectTokenSuccess();
        expectUserInfo("{\"kakao_account\": {\"email\": \"a@kakao.test\"}}");

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