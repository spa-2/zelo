package com.server.zelo.domain.user.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 이메일은_소문자로_변환된다() {
        // when
        LoginRequest request = new LoginRequest("Jieun@Example.com", "DemoPass!2026");

        // then
        assertThat(request.email()).isEqualTo("jieun@example.com");
    }

    @Test
    void 이메일이_null이어도_예외없이_생성된다() {
        // when
        LoginRequest request = new LoginRequest(null, "DemoPass!2026");

        // then
        assertThat(request.email()).isNull();
    }

    @Test
    void 올바른_이메일과_비밀번호면_검증을_통과한다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "DemoPass!2026");

        // when & then
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void 이메일_형식이_올바르지_않으면_검증에_실패한다() {
        // given
        LoginRequest request = new LoginRequest("not-an-email", "DemoPass!2026");

        // when & then
        assertThat(validator.validateProperty(request, "email")).isNotEmpty();
    }

    @Test
    void 이메일이_비어있으면_검증에_실패한다() {
        // given
        LoginRequest request = new LoginRequest(" ", "DemoPass!2026");

        // when & then
        assertThat(validator.validateProperty(request, "email")).isNotEmpty();
    }

    @Test
    void 비밀번호가_비어있으면_검증에_실패한다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", " ");

        // when & then
        assertThat(validator.validateProperty(request, "password")).isNotEmpty();
    }

    @Test
    void 비밀번호가_72자면_검증을_통과한다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "Aa1!" + "a".repeat(68));

        // when & then
        assertThat(validator.validateProperty(request, "password")).isEmpty();
    }

    @Test
    void 비밀번호가_73자를_넘으면_검증에_실패한다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "Aa1!" + "a".repeat(69));

        // when & then
        assertThat(validator.validateProperty(request, "password")).isNotEmpty();
    }

    @Test
    void 비밀번호에_ASCII가_아닌_문자가_있으면_검증에_실패한다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "비밀번호Aa1!");

        // when & then
        assertThat(validator.validateProperty(request, "password")).isNotEmpty();
    }
}
