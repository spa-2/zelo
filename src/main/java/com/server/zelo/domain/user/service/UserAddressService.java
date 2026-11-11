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
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserAddressService {

    private final UserAddressRepository userAddressRepository;

    public UserAddress createUserAddress(User user, String place, String recipientName, String recipientPhone,
                                         String zipCode, String address, boolean isDefault) {
        return userAddressRepository.save(
                UserAddress.of(user, place, recipientName, recipientPhone, zipCode, address, isDefault));
    }


    @Transactional(readOnly = true)
    public List<GetUserAddressResponse> getUserAddresses(Long userId) {
        return userAddressRepository.findAllActiveByUserId(userId).stream()
                .map(GetUserAddressResponse::from)
                .toList();
    }

    // 기본 배송지가 없을 때의 처리를 호출하는 쪽에서 정할 수 있게 Optional로 돌려준다
    @Transactional(readOnly = true)
    public Optional<UserAddress> findDefaultAddress(Long userId) {
        return userAddressRepository.findDefaultByUserId(userId);
    }

    @Transactional(readOnly = true)
    public UserAddress getDefaultAddress(Long userId) {
        return findDefaultAddress(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFAULT_ADDRESS_NOT_FOUND));
    }
    @Transactional
    public UpdateUserAddressResponse updateUserAddress(Long userId, Long addressId, UpdateUserAddressRequest request) {
        UserAddress userAddress = userAddressRepository.findActiveByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_ADDRESS_NOT_FOUND));

        if (userAddressRepository.existsByUserIdAndPlaceAndIdNot(userId, request.place(), addressId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_ADDRESS_PLACE);
        }
        userAddress.validateKeepDefault(request.isDefault());

        if (request.isDefault() && !userAddress.isDefault()) {
            releaseDefaultAddress(userId);
            userAddress.changeToDefault();
        }

        userAddress.update(request.place(), request.recipientName(), request.recipientPhone(),
                request.zipCode(), request.address());
        return UpdateUserAddressResponse.from(userAddress);
    }

    @Transactional
    public void deleteUserAddress(Long userId, Long addressId) {
        UserAddress userAddress = userAddressRepository.findActiveByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_ADDRESS_NOT_FOUND));
        userAddress.validateDeletable();
        userAddressRepository.delete(userAddress);
    }

    public void validatePlaceNotDuplicated(Long userId, String place) {
        if (userAddressRepository.existsByUserIdAndPlace(userId, place)) {
            throw new BusinessException(ErrorCode.DUPLICATE_ADDRESS_PLACE);
        }
    }

    public void releaseDefaultAddress(Long userId) {
        userAddressRepository.findDefaultByUserId(userId).ifPresent(UserAddress::releaseDefault);
    }
}
