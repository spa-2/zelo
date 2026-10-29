package com.server.zelo.domain.user.emailverification.dto;

import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SendEmailRequest(
        @Email
        @NotBlank
        @Size(max = 50)
        String email,

        @NotNull
        EmailVerificationPurpose purpose
) {
        public SendEmailRequest{
                email = User.normalizeEmail(email);
        }
}
