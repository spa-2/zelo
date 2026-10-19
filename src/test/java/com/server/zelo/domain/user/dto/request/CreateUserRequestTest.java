package com.server.zelo.domain.user.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateUserRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 이메일은_소문자로_변환된다() {
        // when
        CreateUserRequest request = new CreateUserRequest(
                "TEST@Example.com", "Password1!", "Password1!", "홍길동",
                "010-1234-5678", "집", "서울 특별시 그곳", "12345");

        // then
        assertThat(request.email()).isEqualTo("test@example.com");
    }

    @Test
    void 이메일이_null이어도_예외없이_생성된다() {
        // when
        CreateUserRequest request = new CreateUserRequest(
                null, "Password1!", "Password1!", "홍길동",
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

    private CreateUserRequest requestWithPassword(String password) {
        return new CreateUserRequest(
                "test@example.com", password, password, "홍길동",
                "010-1234-5678", "집", "서울 특별시 그곳", "12345");
    }
}
