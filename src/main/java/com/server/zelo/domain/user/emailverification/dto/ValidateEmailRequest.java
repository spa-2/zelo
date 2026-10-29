package com.server.zelo.domain.user.emailverification.dto;

import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.entity.User;
import jakarta.validation.constraints.*;

public record ValidateEmailRequest(
        @Email
        @NotBlank
        @Size(max = 50)
        String email,

        @NotNull
        EmailVerificationPurpose purpose,

        @NotBlank
        @Pattern(regexp = "\\d{6}") // code 형식인 정수 6자리를 검증하여 오타에 대한 실패 횟수 증가를 방지
        String code
) {
    public ValidateEmailRequest{
        email = User.normalizeEmail(email);
    }
}
