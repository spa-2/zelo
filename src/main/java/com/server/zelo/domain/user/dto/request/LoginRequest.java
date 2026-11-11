package com.server.zelo.domain.user.dto.request;

import com.server.zelo.domain.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Email
        @NotBlank
        @Size(max = 50)
        String email,

        @NotBlank
        @Size(max = 72)
        // 가입 때와 같은 ASCII만 허용해 BCrypt 72바이트 제한을 넘는 입력이 검증 단계에서 걸러지게 함 (비밀번호 규칙은 노출하지 않으려고 형식 검사는 안 함)
        @Pattern(regexp = "^[\\x21-\\x7E]+$")
        String password
) {

    // 가입 때 소문자로 저장하므로 락 키와 조회 모두 같은 값을 쓰도록 요청 시점에 정규화함
    public LoginRequest {
        email = User.normalizeEmail(email);
    }
}
