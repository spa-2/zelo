package com.server.zelo.domain.user.repository;

import com.server.zelo.domain.user.entity.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {
    Optional<UserAddress> findByUserIdAndIsDefaultTrueAndDeleteAtIsNull(Long userId);

    // 삭제되지 않은 기본 배송지를 찾는다
    default Optional<UserAddress> findDefaultByUserId(Long userId) {
        return findByUserIdAndIsDefaultTrueAndDeleteAtIsNull(userId);
    }

    List<UserAddress> findAllByUserIdAndDeleteAtIsNullOrderByIsDefaultDescIdDesc(Long userId);

    // 삭제되지 않은 배송지를 기본 배송지 먼저, 나머지는 최근 등록 순으로 찾는다
    default List<UserAddress> findAllActiveByUserId(Long userId) {
        return findAllByUserIdAndDeleteAtIsNullOrderByIsDefaultDescIdDesc(userId);
    }

    Optional<UserAddress> findByIdAndUserIdAndDeleteAtIsNull(Long id, Long userId);

    // 본인의 삭제되지 않은 배송지를 찾는다
    default Optional<UserAddress> findActiveByIdAndUserId(Long id, Long userId) {
        return findByIdAndUserIdAndDeleteAtIsNull(id, userId);
    }

}
