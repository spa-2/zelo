package com.server.zelo.domain.user.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CreateUserRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 이메일은_소문자로_변환된다() {
        // when
        CreateUserRequest request = new CreateUserRequest(
                "TEST@Example.com", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXw","Password1!", "Password1!", "홍길동",
                "010-1234-5678", "집", "서울 특별시 그곳", "12345");

        // then
        assertThat(request.email()).isEqualTo("test@example.com");
    }

    @Test
    void 이메일이_null이어도_예외없이_생성된다() {
        // when
        CreateUserRequest request = new CreateUserRequest(
                null, "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXw","Password1!", "Password1!", "홍길동",
                "010-1234-5678", "집", "서울 특별시 그곳", "12345");

        // then
        assertThat(request.email()).isNull();
    }

    @Test
    void 비밀번호가_72자면_검증을_통과한다() {
        // given
        String password = "Aa1!" + "a".repeat(68);

        // when
        CreateUserRequest request = requestWithPassword(password);

        // then
        assertThat(validator.validateProperty(request, "password")).isEmpty();
    }

    @Test
    void 비밀번호가_73자를_넘으면_검증에_실패한다() {
        // given
        String password = "Aa1!" + "a".repeat(69);

        // when
        CreateUserRequest request = requestWithPassword(password);

        // then
        assertThat(validator.validateProperty(request, "password")).hasSize(1);
    }

    @Test
    void 비밀번호에_한글이_섞이면_검증에_실패한다() {
        // given
        String password = "Aa1!비밀번호";

        // when
        CreateUserRequest request = requestWithPassword(password);

        // then
        assertThat(validator.validateProperty(request, "password")).hasSize(1);
    }

    @Test
    void 비밀번호에_공백이_있으면_검증에_실패한다() {
        // given
        String password = "Aa1! aaaa";

        // when
        CreateUserRequest request = requestWithPassword(password);

        // then
        assertThat(validator.validateProperty(request, "password")).hasSize(1);
    }

    @Test
    void 영문_숫자_특수문자를_모두_포함한_비밀번호는_검증을_통과한다() {
        // given
        String password = "DemoPass!2026";

        // when
        CreateUserRequest request = requestWithPassword(password);

        // then
        assertThat(validator.validateProperty(request, "password")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "short", "A", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void 이메일_인증_티켓이_비어있거나_43자가_아니면_검증에_실패한다(String ticket) {
        // given
        CreateUserRequest request = requestWithTicket(ticket);

        // when & then 32바이트를 Base64url로 인코딩한 길이(43자)만 허용
        assertThat(validator.validateProperty(request, "emailVerificationTicket")).isNotEmpty();
    }

    @Test
    void 이메일_인증_티켓이_43자이면_검증을_통과한다() {
        // given
        CreateUserRequest request = requestWithTicket("A".repeat(43));

        // when & then
        assertThat(validator.validateProperty(request, "emailVerificationTicket")).isEmpty();
    }

    private CreateUserRequest requestWithTicket(String ticket) {
        return new CreateUserRequest(
                "test@example.com", ticket, "DemoPass!2026", "DemoPass!2026", "홍길동",
                "010-1234-5678", "집", "서울 특별시 그곳", "12345");
    }

    private CreateUserRequest requestWithPassword(String password) {
        return new CreateUserRequest(
                "test@example.com","dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXw", password, password, "홍길동",
                "010-1234-5678", "집", "서울 특별시 그곳", "12345");
    }
}
