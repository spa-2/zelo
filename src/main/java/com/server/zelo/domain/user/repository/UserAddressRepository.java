package com.server.zelo.domain.user.repository;

import com.server.zelo.domain.user.entity.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {

    Optional<UserAddress> findByUserIdAndIsDefaultTrue(Long userId);

    List<UserAddress> findAllByUserIdOrderByIsDefaultDescIdDesc(Long userId);

    Optional<UserAddress> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndPlace(Long userId, String place);

    boolean existsByUserIdAndPlaceAndIdNot(Long userId, String place, Long id);

    // 기본 배송지를 찾는다
    default Optional<UserAddress> findDefaultByUserId(Long userId) {
        return findByUserIdAndIsDefaultTrue(userId);
    }

    // 기본 배송지 먼저, 나머지는 최근 등록 순으로 찾는다
    default List<UserAddress> findAllActiveByUserId(Long userId) {
        return findAllByUserIdOrderByIsDefaultDescIdDesc(userId);
    }

    // 본인의 배송지를 찾는다
    default Optional<UserAddress> findActiveByIdAndUserId(Long id, Long userId) {
        return findByIdAndUserId(id, userId);
    }
}