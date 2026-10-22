package com.server.zelo.domain.user.repository;

import com.server.zelo.domain.user.entity.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {
    Optional<UserAddress> findByUserIdAndIsDefaultTrueAndDeleteAtIsNull(Long userId);

    // 삭제되지 않은 기본 배송지를 찾는다
    default Optional<UserAddress> findDefaultByUserId(Long userId) {
        return findByUserIdAndIsDefaultTrueAndDeleteAtIsNull(userId);
    }
}
