package com.server.zelo.domain.user.dto.response;

import com.server.zelo.domain.user.entity.UserAddress;

public record CreateUserAddressResponse(
        Long addressId,
        String place,
        String recipientName,
        String recipientPhone,
        String zipCode,
        String address,
        boolean isDefault
) {

    public static CreateUserAddressResponse from(UserAddress userAddress) {
        return new CreateUserAddressResponse(userAddress.getId(), userAddress.getPlace(),
                userAddress.getRecipientName(), userAddress.getRecipientPhone(), userAddress.getZipCode(),
                userAddress.getAddress(), userAddress.isDefault());
    }
}