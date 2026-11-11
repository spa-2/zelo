package com.server.zelo.domain.user.dto.request;

import com.server.zelo.domain.user.entity.User;
import jakarta.validation.constraints.*;

public record ResetPasswordRequest(
        @Email
        @NotBlank
        @Size(max = 50)
        String email,

        @NotBlank
        @Size(min =43, max = 43)
        // 32바이트를 base64URL로 인코딩하면 43자가 되어 43자로 받음
        String emailVerificationTicket,

        @NotBlank
        @Size(min = 8, max = 72)
        // BCrypt는 72바이트를 넘으면 예외가 나서, 한 글자가 1바이트인 ASCII 문자만 허용해 72자 = 72바이트로 맞춤
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d])[\\x21-\\x7E]+$")
        String password,

        @NotBlank
        @Size(min = 8, max = 255)
        String passwordConfirm
) {
        public ResetPasswordRequest {
                email = User.normalizeEmail(email);
        }
}
