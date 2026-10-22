package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.request.UpdateUserAddressRequest;
import com.server.zelo.domain.user.dto.response.GetUserAddressResponse;
import com.server.zelo.domain.user.dto.response.UpdateUserAddressResponse;
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

    // 기존 기본 배송지 변경과 수정이 같이 반영되거나 같이 롤백되어야 해서 트랜잭션을 묶음
    @Transactional
    public UpdateUserAddressResponse updateUserAddress(Long userId, Long addressId, UpdateUserAddressRequest request) {
        UserAddress userAddress = userAddressRepository.findActiveByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_ADDRESS_NOT_FOUND));
        userAddress.validateKeepDefault(request.isDefault());

        if (request.isDefault() && !userAddress.isDefault()) {
            releaseDefaultAddress(userId);
            userAddress.changeToDefault();
        }

        userAddress.update(request.place(), request.recipientName(), request.recipientPhone(),
                request.zipCode(), request.address());
        return UpdateUserAddressResponse.from(userAddress);
    }
}
