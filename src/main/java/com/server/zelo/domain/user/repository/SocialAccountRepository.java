package com.server.zelo.domain.user.repository;

import com.server.zelo.domain.user.entity.SocialAccount;
import com.server.zelo.domain.user.entity.SocialProvider;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    // role을 get해야 되서 N+1 방지를 위해 추가
    @EntityGraph(attributePaths = "user")
    Optional<SocialAccount> findByProviderAndProviderId(SocialProvider socialProvider, String providerId);
}
