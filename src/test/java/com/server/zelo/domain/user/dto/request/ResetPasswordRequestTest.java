package com.server.zelo.domain.user.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ResetPasswordRequestTest {

    private static final String TICKET = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXw";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 이메일은_소문자로_변환된다() {
        // when
        ResetPasswordRequest request = new ResetPasswordRequest("TEST@Example.com", TICKET, "DemoPass!2026", "DemoPass!2026");

        // then 인증 때 쓴 이메일과 같은 키로 찾으려면 대소문자가 같아야 함
        assertThat(request.email()).isEqualTo("test@example.com");
    }

    @Test
    void 이메일이_null이어도_예외없이_생성된다() {
        // when
        ResetPasswordRequest request = new ResetPasswordRequest(null, TICKET, "DemoPass!2026", "DemoPass!2026");

        // then
        assertThat(request.email()).isNull();
    }

    @Test
    void 올바른_요청은_검증을_통과한다() {
        // given
        ResetPasswordRequest request = new ResetPasswordRequest("test@example.com", TICKET, "DemoPass!2026", "DemoPass!2026");

        // when & then
        assertThat(validator.validate(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-an-email"})
    void 이메일이_올바르지_않으면_검증에_실패한다(String email) {
        // given
        ResetPasswordRequest request = new ResetPasswordRequest(email, TICKET, "DemoPass!2026", "DemoPass!2026");

        // when & then
        assertThat(validator.validateProperty(request, "email")).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "short", "A", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void 이메일_인증_티켓이_비어있거나_43자가_아니면_검증에_실패한다(String ticket) {
        // given
        ResetPasswordRequest request = new ResetPasswordRequest("test@example.com", ticket, "DemoPass!2026", "DemoPass!2026");

        // when & then 32바이트를 Base64url로 인코딩한 길이(43자)만 허용
        assertThat(validator.validateProperty(request, "emailVerificationTicket")).isNotEmpty();
    }

    @Test
    void 비밀번호가_72자면_검증을_통과한다() {
        // given
        String password = "Aa1!" + "a".repeat(68);

        // when & then
        assertThat(validator.validateProperty(requestWithPassword(password), "password")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Aa1!aaa", "Aa1!비밀번호", "Aa1! aaaa", "aaaaaaaa1", "AAAAAAAA!", "11111111!"})
    void 길이_문자_조합_규칙을_어기면_검증에_실패한다(String password) {
        // when & then 7자 이하, 한글, 공백, 영문·숫자·특수문자 중 하나라도 빠진 경우
        assertThat(validator.validateProperty(requestWithPassword(password), "password")).isNotEmpty();
    }

    @Test
    void 비밀번호가_73자를_넘으면_검증에_실패한다() {
        // given
        String password = "Aa1!" + "a".repeat(69);

        // when & then BCrypt는 72바이트를 넘으면 예외가 남
        assertThat(validator.validateProperty(requestWithPassword(password), "password")).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "short"})
    void 비밀번호_확인이_비어있거나_8자_미만이면_검증에_실패한다(String passwordConfirm) {
        // given
        ResetPasswordRequest request = new ResetPasswordRequest("test@example.com", TICKET, "DemoPass!2026", passwordConfirm);

        // when & then
        assertThat(validator.validateProperty(request, "passwordConfirm")).isNotEmpty();
    }

    private ResetPasswordRequest requestWithPassword(String password) {
        return new ResetPasswordRequest("test@example.com", TICKET, password, password);
    }
}
