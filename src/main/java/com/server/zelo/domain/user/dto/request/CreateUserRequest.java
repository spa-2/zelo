package com.server.zelo.domain.user.dto.request;

import com.server.zelo.domain.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
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
        String passwordConfirm,

        @NotBlank
        @Size(min = 1,max = 50)
        String name,

        @NotBlank
        @Size(min = 11,max = 255)
        @Pattern(regexp = "^01(?:0|1|[6-9])-(?:\\d{3}|\\d{4})-\\d{4}$|^01(?:0|1|[6-9])\\d{7,8}$")
        String phoneNumber,

        @NotBlank
        @Size(max = 20)
        String place,

        @NotBlank
        @Size(min = 2, max = 200)
        String addresses,

        @NotBlank
        @Size(max = 10)
        String zipCode
) {
        // 전화번호 변경로직을 위해 record의 생성자 대신 일반 생성자를 활용
        public CreateUserRequest(String email, String emailVerificationTicket, String password, String passwordConfirm, String name, String phoneNumber, String place, String addresses, String zipCode) {
                // 락 키도 이 값으로 만들어지므로 요청 시점에 정규화함
                this.email = User.normalizeEmail(email);
                this.emailVerificationTicket = emailVerificationTicket;
                this.password = password;
                this.passwordConfirm = passwordConfirm;
                this.name = name;
                this.place = place;
                this.addresses = addresses;
                this.zipCode = zipCode;

                // 하이픈 제거 후 정규식으로 재조립하여 필드에 대입(사용자는 - 을 넣어도 넣지않아도 - 가 붙어서 저장됨)
                this.phoneNumber = phoneNumber == null
                        ? null
                        : phoneNumber.replace("-", "").replaceFirst("^(02|[0-9]{3})([0-9]{3,4})([0-9]{4})$", "$1-$2-$3");

        }
}
