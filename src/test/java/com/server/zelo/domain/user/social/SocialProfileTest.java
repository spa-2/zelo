package com.server.zelo.domain.user.social;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.entity.SocialProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialProfileTest {

    @Test
    void of는_받은_값을_그대로_담는다() {
        // when
        SocialProfile profile = SocialProfile.of(SocialProvider.GOOGLE, "1234567890", "jieun@gmail.com", true, "지은");

        // then
        assertThat(profile.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(profile.providerId()).isEqualTo("1234567890");
        assertThat(profile.email()).isEqualTo("jieun@gmail.com");
        assertThat(profile.emailVerified()).isTrue();
        assertThat(profile.name()).isEqualTo("지은");
    }

    // ===== 신규 가입 가능 여부 =====

    @Test
    void 인증된_이메일이_있으면_가입_검증을_통과한다() {
        // given
        SocialProfile profile = profile("jieun@gmail.com", true, "지은");

        // when & then
        assertThatCode(profile::validateForSignup).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void 이메일이_없거나_비어있으면_가입_검증에_실패한다(String email) {
        // given
        SocialProfile profile = profile(email, true, "지은");

        // when & then
        assertThatThrownBy(profile::validateForSignup)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOCIAL_EMAIL_REQUIRED);
    }

    // 인증되지 않은 이메일로 가입시키면 남의 이메일로 가입하거나 계정을 가로챌 수 있음
    @Test
    void 이메일이_인증되지_않았으면_가입_검증에_실패한다() {
        // given
        SocialProfile profile = profile("jieun@gmail.com", false, "지은");

        // when & then
        assertThatThrownBy(profile::validateForSignup)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOCIAL_EMAIL_REQUIRED);
    }

    // 이미 가입한 사용자는 이메일이 없어도 로그인되어야 해서, 가입 조건을 프로필을 만들 때 강제하면 안 됨
    @Test
    void 이메일이_없는_프로필도_만들_수_있다() {
        // when & then
        assertThatCode(() -> profile(null, false, "지은")).doesNotThrowAnyException();
    }

    // ===== 가입 시 저장할 이름 =====

    @Test
    void 이름이_있으면_그_이름을_쓴다() {
        assertThat(profile("jieun@gmail.com", true, "지은").displayName()).isEqualTo("지은");
    }

    @Test
    void 이름_앞뒤의_공백은_제거한다() {
        assertThat(profile("jieun@gmail.com", true, "  지은  ").displayName()).isEqualTo("지은");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void 이름이_없거나_비어있으면_이메일_앞부분을_쓴다(String name) {
        // given
        SocialProfile profile = profile("jieun@gmail.com", true, name);

        // when & then
        // users.name이 NOT NULL이라서 이름이 없는 채로 저장하면 가입 마지막 단계에서 실패함
        assertThat(profile.displayName()).isEqualTo("jieun");
    }

    @Test
    void 이름이_50자이면_그대로_쓴다() {
        // given
        String name = "가".repeat(50);

        // when & then
        assertThat(profile("jieun@gmail.com", true, name).displayName()).isEqualTo(name);
    }

    @Test
    void 이름이_50자를_넘으면_50자로_자른다() {
        // given
        String name = "가".repeat(51);

        // when
        String displayName = profile("jieun@gmail.com", true, name).displayName();

        // then
        // users.name의 길이 제한이 50자라서 넘으면 저장에서 실패함
        assertThat(displayName).hasSize(50).isEqualTo("가".repeat(50));
    }

    @Test
    void 이름이_없고_이메일_앞부분이_50자를_넘어도_50자로_자른다() {
        // given
        String email = "a".repeat(60) + "@gmail.com";

        // when
        String displayName = profile(email, true, null).displayName();

        // then
        assertThat(displayName).hasSize(50).isEqualTo("a".repeat(50));
    }

    private SocialProfile profile(String email, boolean emailVerified, String name) {
        return SocialProfile.of(SocialProvider.GOOGLE, "1234567890", email, emailVerified, name);
    }
}
