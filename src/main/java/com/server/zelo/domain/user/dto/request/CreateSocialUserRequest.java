package com.server.zelo.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateSocialUserRequest(
        @NotBlank
        @Size(min = 1, max = 255)
        String signupTicket,

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
    public CreateSocialUserRequest(String signupTicket, String phoneNumber, String place, String addresses, String zipCode) {
        this.signupTicket = signupTicket;
        this.place = place;
        this.addresses = addresses;
        this.zipCode = zipCode;

        this.phoneNumber = phoneNumber == null
                ? null
                : phoneNumber.replace("-", "").replaceFirst("^(02|[0-9]{3})([0-9]{3,4})([0-9]{4})$", "$1-$2-$3");

    }
}
