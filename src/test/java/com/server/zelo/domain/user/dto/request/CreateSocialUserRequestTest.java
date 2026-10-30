package com.server.zelo.domain.user.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class
CreateSocialUserRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 올바른_값이면_검증을_통과한다() {
        // when & then
        assertThat(validator.validate(request("ticket", "010-1234-5678", "집", "서울 강남구", "12345"))).isEmpty();
    }

    @Test
    void 하이픈_없는_전화번호는_하이픈을_붙여_정규화한다() {
        // when
        CreateSocialUserRequest request = request("ticket", "01012345678", "집", "서울 강남구", "12345");

        // then
        assertThat(request.phoneNumber()).isEqualTo("010-1234-5678");
    }

    @Test
    void 전화번호가_null이어도_예외없이_생성된다() {
        // when
        CreateSocialUserRequest request = request("ticket", null, "집", "서울 강남구", "12345");

        // then
        assertThat(request.phoneNumber()).isNull();
        assertThat(validator.validateProperty(request, "phoneNumber")).isNotEmpty();
    }

    @Test
    void 번호표가_비어있으면_검증에_실패한다() {
        assertThat(validator.validateProperty(
                request(" ", "010-1234-5678", "집", "서울 강남구", "12345"), "signupTicket")).isNotEmpty();
    }

    @Test
    void 전화번호_형식이_올바르지_않으면_검증에_실패한다() {
        assertThat(validator.validateProperty(
                request("ticket", "02-1234-5678", "집", "서울 강남구", "12345"), "phoneNumber")).isNotEmpty();
        assertThat(validator.validateProperty(
                request("ticket", "abc", "집", "서울 강남구", "12345"), "phoneNumber")).isNotEmpty();
    }

    @Test
    void 장소가_비어있거나_20자를_넘으면_검증에_실패한다() {
        assertThat(validator.validateProperty(
                request("ticket", "010-1234-5678", " ", "서울 강남구", "12345"), "place")).isNotEmpty();
        assertThat(validator.validateProperty(
                request("ticket", "010-1234-5678", "가".repeat(21), "서울 강남구", "12345"), "place")).isNotEmpty();
    }

    @Test
    void 주소가_1자이거나_200자를_넘으면_검증에_실패한다() {
        assertThat(validator.validateProperty(
                request("ticket", "010-1234-5678", "집", "가", "12345"), "addresses")).isNotEmpty();
        assertThat(validator.validateProperty(
                request("ticket", "010-1234-5678", "집", "가".repeat(201), "12345"), "addresses")).isNotEmpty();
    }

    @Test
    void 우편번호가_비어있거나_10자를_넘으면_검증에_실패한다() {
        assertThat(validator.validateProperty(
                request("ticket", "010-1234-5678", "집", "서울 강남구", " "), "zipCode")).isNotEmpty();
        assertThat(validator.validateProperty(
                request("ticket", "010-1234-5678", "집", "서울 강남구", "1".repeat(11)), "zipCode")).isNotEmpty();
    }

    private CreateSocialUserRequest request(String signupTicket, String phoneNumber,
                                            String place, String addresses, String zipCode) {
        return new CreateSocialUserRequest(signupTicket, phoneNumber, place, addresses, zipCode);
    }
}
