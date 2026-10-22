package com.server.zelo.domain.user.entity;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void 이메일은_소문자로_저장된다() {
        // when
        User user = User.of("TEST@Example.com", "encodedPassword", "홍길동", "010-1234-5678");

        // then
        assertThat(user.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void null_이메일은_그대로_null이다() {
        // when & then
        assertThat(User.normalizeEmail(null)).isNull();
    }

    @Test
    void 서버_로케일이_터키어여도_이메일이_같은_결과로_변환된다() {
        // given
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        try {
            // when
            String result = User.normalizeEmail("INFO@EXAMPLE.COM");

            // then
            assertThat(result).isEqualTo("info@example.com");
        } finally {
            // 다른 테스트에 로케일이 남지 않게 복구
            Locale.setDefault(original);
        }
    }

    @Test
    void 소셜_가입_사용자는_비밀번호가_없다() {
        // when
        User user = User.ofSocial("jieun@gmail.com", "지은", "010-1234-5678");

        // then
        assertThat(user.isPasswordNull()).isTrue();
        assertThat(user.getPassword()).isNull();
    }

    @Test
    void 소셜_가입_사용자의_이름은_변환하지_않고_이메일만_소문자로_저장된다() {
        // when
        User user = User.ofSocial("Jieun@Gmail.com", "Jieun Kim", "010-1234-5678");

        // then
        assertThat(user.getName()).isEqualTo("Jieun Kim");
        assertThat(user.getEmail()).isEqualTo("jieun@gmail.com");
        assertThat(user.getPhoneNumber()).isEqualTo("010-1234-5678");
    }

    @Test
    void 일반_가입_사용자는_비밀번호가_있다() {
        // when
        User user = User.of("test@example.com", "encodedPassword", "홍길동", "010-1234-5678");

        // then
        assertThat(user.isPasswordNull()).isFalse();
    }
}
