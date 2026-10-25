package com.server.zelo.domain.user.dto.response;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SocialLoginResponseTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void 기존_회원_결과는_가입이_필요_없고_액세스_토큰만_담는다() {
        // when
        SocialLoginResponse response = SocialLoginResponse.from(new ExistingMemberResult("accessToken", "refreshToken"));

        // then
        assertThat(response.signupRequired()).isFalse();
        assertThat(response.accessToken()).isEqualTo("accessToken");
        assertThat(response.signupToken()).isNull();
        assertThat(response.email()).isNull();
        assertThat(response.name()).isNull();
    }

    @Test
    void 신규_회원_결과는_가입이_필요하고_번호표와_프로필을_담는다() {
        // when
        SocialLoginResponse response = SocialLoginResponse.from(new NewMemberResult("ticket", "jieun@gmail.com", "지은"));

        // then
        assertThat(response.signupRequired()).isTrue();
        assertThat(response.signupToken()).isEqualTo("ticket");
        assertThat(response.email()).isEqualTo("jieun@gmail.com");
        assertThat(response.name()).isEqualTo("지은");
        assertThat(response.accessToken()).isNull();
    }

    @Test
    void 기존_회원_응답의_JSON에는_값이_없는_필드가_빠진다() {
        // when
        String json = jsonMapper.writeValueAsString(
                SocialLoginResponse.from(new ExistingMemberResult("accessToken", "refreshToken")));

        // then
        assertThat(json).contains("signupRequired", "accessToken")
                .doesNotContain("signupToken", "email", "name", "refreshToken");
    }

    @Test
    void 신규_회원_응답의_JSON에는_액세스_토큰이_없다() {
        // when
        String json = jsonMapper.writeValueAsString(
                SocialLoginResponse.from(new NewMemberResult("ticket", "jieun@gmail.com", "지은")));

        // then
        assertThat(json).contains("signupRequired", "signupToken", "email", "name").doesNotContain("accessToken");
    }
}
