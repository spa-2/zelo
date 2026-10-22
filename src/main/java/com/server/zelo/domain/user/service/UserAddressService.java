package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.response.GetUserAddressResponse;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import com.server.zelo.domain.user.repository.UserAddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    @Transactional(readOnly = true)
    public List<GetUserAddressResponse> getUserAddresses(Long userId) {
        return userAddressRepository.findAllActiveByUserId(userId).stream()
                .map(GetUserAddressResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserAddress getDefaultAddress(Long userId) {
        return userAddressRepository.findDefaultByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFAULT_ADDRESS_NOT_FOUND));
    }
}
