package com.server.zelo.domain.user.service;

import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import com.server.zelo.domain.user.repository.UserAddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserAddressService {

    private final UserAddressRepository userAddressRepository;

    public UserAddress createUserAddress(User user, String place, String recipientName, String recipientPhone,
                                         String zipCode, String address, boolean isDefault) {
        return userAddressRepository.save(
                UserAddress.of(user, place, recipientName, recipientPhone, zipCode, address, isDefault));
    }

    public void releaseDefaultAddress(Long userId) {
        userAddressRepository.findDefaultByUserId(userId).ifPresent(UserAddress::releaseDefault);
    }
}
