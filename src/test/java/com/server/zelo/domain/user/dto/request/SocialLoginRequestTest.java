package com.server.zelo.domain.user.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SocialLoginRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void code와_redirectUri만_있어도_검증을_통과한다() {
        // when & then
        // PKCE를 쓰지 않는 요청이라 codeVerifier는 없어도 됨
        assertThat(validator.validate(new SocialLoginRequest("code", "http://localhost:3000/callback", null))).isEmpty();
    }

    @Test
    void code가_비어있으면_검증에_실패한다() {
        assertThat(validator.validateProperty(
                new SocialLoginRequest(" ", "http://localhost:3000/callback", null), "code")).isNotEmpty();
    }

    @Test
    void code가_255자를_넘으면_검증에_실패한다() {
        assertThat(validator.validateProperty(
                new SocialLoginRequest("a".repeat(256), "http://localhost:3000/callback", null), "code")).isNotEmpty();
    }

    @Test
    void redirectUri가_비어있거나_255자를_넘으면_검증에_실패한다() {
        assertThat(validator.validateProperty(new SocialLoginRequest("code", " ", null), "redirectUri")).isNotEmpty();
        assertThat(validator.validateProperty(
                new SocialLoginRequest("code", "a".repeat(256), null), "redirectUri")).isNotEmpty();
    }

    @Test
    void codeVerifier가_43자에서_128자_사이면_검증을_통과한다() {
        assertThat(validator.validateProperty(
                new SocialLoginRequest("code", "http://localhost:3000/callback", "a".repeat(43)), "codeVerifier")).isEmpty();
        assertThat(validator.validateProperty(
                new SocialLoginRequest("code", "http://localhost:3000/callback", "a".repeat(128)), "codeVerifier")).isEmpty();
    }

    @Test
    void codeVerifier가_빈_문자열이거나_범위를_벗어나면_검증에_실패한다() {
        // 빈 문자열을 그대로 구글에 보내면 구글이 거절하므로 요청 단계에서 막음
        assertThat(validator.validateProperty(
                new SocialLoginRequest("code", "http://localhost:3000/callback", ""), "codeVerifier")).isNotEmpty();
        assertThat(validator.validateProperty(
                new SocialLoginRequest("code", "http://localhost:3000/callback", "a".repeat(42)), "codeVerifier")).isNotEmpty();
        assertThat(validator.validateProperty(
                new SocialLoginRequest("code", "http://localhost:3000/callback", "a".repeat(129)), "codeVerifier")).isNotEmpty();
    }
}
