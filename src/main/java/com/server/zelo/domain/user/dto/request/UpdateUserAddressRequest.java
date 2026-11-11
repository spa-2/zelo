package com.server.zelo.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateUserAddressRequest(
        @NotBlank
        @Size(max = 20)
        String place,

        @NotBlank
        @Size(max = 50)
        String recipientName,

        @NotBlank
        @Pattern(regexp = "^01(?:0|1|[6-9])-(?:\\d{3}|\\d{4})-\\d{4}$|^01(?:0|1|[6-9])\\d{7,8}$")
        String recipientPhone,

        @NotBlank
        @Size(max = 10)
        String zipCode,

        @NotBlank
        @Size(min = 2, max = 200)
        String address,

        @NotNull
        Boolean isDefault
) {
}