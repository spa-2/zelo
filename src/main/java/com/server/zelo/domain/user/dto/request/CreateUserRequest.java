package com.server.zelo.domain.user.dto.request;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
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
        @Size(min = 8, max = 255)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).+$")
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
        public CreateUserRequest(String email, String password, String passwordConfirm, String name, String phoneNumber, String place, String addresses, String zipCode) {
                if (!password.equals(passwordConfirm)) {
                        throw new BusinessException(ErrorCode.PASSWORD_CONFIRMATION_MISMATCH);
                }

                this.email = email;
                this.password = password;
                this.passwordConfirm = passwordConfirm;
                this.name = name;
                this.place = place;
                this.addresses = addresses;
                this.zipCode = zipCode;

                // 하이픈 제거 후 정규식으로 재조립하여 필드에 대입(사용자는 - 을 넣어도 넣지않아도 - 가 붙어서 저장됨)
                String cleanNumber = phoneNumber.replace("-", "");
                this.phoneNumber = cleanNumber.replaceFirst("(^02|[0-9]{3})([0-9]{3,4})([0-9]{4})$", "$1-$2-$3");
        }
}
