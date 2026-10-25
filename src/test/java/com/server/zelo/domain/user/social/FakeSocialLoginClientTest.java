package com.server.zelo.domain.user.social;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.entity.SocialProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeSocialLoginClientTest {

    private static final String REDIRECT_URI = "http://localhost:3000/callback";

    private final FakeSocialLoginClient client = new FakeSocialLoginClient();

    @Test
    void 제공자는_GOOGLE이다() {
        assertThat(client.getProvider()).isEqualTo(SocialProvider.GOOGLE);
    }

    // ===== 정상 프로필 =====

    @Test
    void fake_로_시작하는_code는_code에서_만든_정상_프로필을_돌려준다() {
        // when
        SocialProfile profile = client.getProfile("fake-jieun", REDIRECT_URI, null);

        // then
        assertThat(profile.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(profile.providerId()).isEqualTo("fake-jieun");
        assertThat(profile.email()).isEqualTo("fake-jieun@fake.example.com");
        assertThat(profile.emailVerified()).isTrue();
        assertThat(profile.name()).isEqualTo("fake-jieun");
    }

    // 가입한 뒤 같은 code로 다시 로그인했을 때 기존 회원으로 인식되려면 항상 같은 사람이어야 함
    @Test
    void 같은_code는_항상_같은_프로필이다() {
        assertThat(client.getProfile("fake-jieun", REDIRECT_URI, null))
                .isEqualTo(client.getProfile("fake-jieun", REDIRECT_URI, null));
    }

    @Test
    void 다른_code는_다른_사람이다() {
        // when
        SocialProfile first = client.getProfile("fake-first", REDIRECT_URI, null);
        SocialProfile second = client.getProfile("fake-second", REDIRECT_URI, null);

        // then
        assertThat(first.providerId()).isNotEqualTo(second.providerId());
        assertThat(first.email()).isNotEqualTo(second.email());
    }

    @Test
    void redirectUri와_codeVerifier는_결과에_영향을_주지_않는다() {
        assertThat(client.getProfile("fake-jieun", "http://other.example.com", "a".repeat(43)))
                .isEqualTo(client.getProfile("fake-jieun", REDIRECT_URI, null));
    }

    // ===== 가입 조건을 확인하는 시나리오 =====

    @Test
    void no_email은_이메일이_없는_프로필이다() {
        // when
        SocialProfile profile = client.getProfile("no-email", REDIRECT_URI, null);

        // then
        assertThat(profile.email()).isNull();
        assertThat(profile.providerId()).isEqualTo("no-email");
    }

    @Test
    void unverified는_이메일은_있지만_인증되지_않은_프로필이다() {
        // when
        SocialProfile profile = client.getProfile("unverified", REDIRECT_URI, null);

        // then
        assertThat(profile.email()).isEqualTo("unverified@fake.example.com");
        assertThat(profile.emailVerified()).isFalse();
    }

    @Test
    void no_name은_이름이_없는_프로필이다() {
        // when
        SocialProfile profile = client.getProfile("no-name", REDIRECT_URI, null);

        // then
        assertThat(profile.name()).isNull();
        // 이름이 없어도 가입은 가능해야 해서 이메일은 정상이어야 함
        assertThat(profile.email()).isEqualTo("no-name@fake.example.com");
        assertThat(profile.emailVerified()).isTrue();
    }

    @Test
    void long_name은_50자를_넘는_이름을_가진_정상_프로필이다() {
        // when
        SocialProfile profile = client.getProfile("long-name", REDIRECT_URI, null);

        // then
        assertThat(profile.name()).hasSizeGreaterThan(50);
        // 이메일이 없으면 가입 검증에서 먼저 거부되어 이름 보정까지 도달하지 못함
        assertThat(profile.email()).isEqualTo("long-name@fake.example.com");
        assertThat(profile.emailVerified()).isTrue();
        assertThat(profile.displayName()).hasSize(50);
    }

    @Test
    void long_name은_호출할_때마다_같은_프로필이다() {
        assertThat(client.getProfile("long-name", REDIRECT_URI, null))
                .isEqualTo(client.getProfile("long-name", REDIRECT_URI, null));
    }

    // ===== 실패 시나리오 =====

    @Test
    void rejected는_제공자가_code를_거절한_상황이다() {
        assertThatThrownBy(() -> client.getProfile("rejected", REDIRECT_URI, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOCIAL_AUTH_FAILED);
    }

    @Test
    void provider_down은_제공자_장애_상황이다() {
        assertThatThrownBy(() -> client.getProfile("provider-down", REDIRECT_URI, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anything", "", "FAKE-jieun", "4/0AfJohXn-real-google-code"})
    void 약속되지_않은_code는_구글이_거절한_것처럼_실패한다(String code) {
        assertThatThrownBy(() -> client.getProfile(code, REDIRECT_URI, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOCIAL_AUTH_FAILED);
    }

    // ===== 빈 등록 조건 =====
    // Fake가 운영에서 켜지면 아무 code로나 로그인되어 인증이 뚫리므로, 설정이 빠졌을 때 켜지지 않는다는 것을 보장함

    @Test
    void mode가_fake일_때만_빈으로_등록된다() {
        new ApplicationContextRunner()
                .withUserConfiguration(FakeSocialLoginClient.class)
                .withPropertyValues("zelo.social.mode=fake")
                .run(context -> assertThat(context).hasSingleBean(FakeSocialLoginClient.class));
    }

    @Test
    void mode가_real이면_빈으로_등록되지_않는다() {
        new ApplicationContextRunner()
                .withUserConfiguration(FakeSocialLoginClient.class)
                .withPropertyValues("zelo.social.mode=real")
                .run(context -> assertThat(context).doesNotHaveBean(FakeSocialLoginClient.class));
    }

    @Test
    void mode_설정이_없으면_빈으로_등록되지_않는다() {
        new ApplicationContextRunner()
                .withUserConfiguration(FakeSocialLoginClient.class)
                .run(context -> assertThat(context).doesNotHaveBean(FakeSocialLoginClient.class));
    }
}
