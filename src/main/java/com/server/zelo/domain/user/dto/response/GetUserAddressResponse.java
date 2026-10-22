package com.server.zelo.domain.user.dto.response;

import com.server.zelo.domain.user.entity.UserAddress;

public record GetUserAddressResponse(
        Long addressId,
        String place,
        String recipientName,
        String recipientPhone,
        String zipCode,
        String address,
        boolean isDefault
) {

    public static GetUserAddressResponse from(UserAddress userAddress) {
        return new GetUserAddressResponse(userAddress.getId(), userAddress.getPlace(),
                userAddress.getRecipientName(), userAddress.getRecipientPhone(), userAddress.getZipCode(),
                userAddress.getAddress(), userAddress.isDefault());
    }
}
