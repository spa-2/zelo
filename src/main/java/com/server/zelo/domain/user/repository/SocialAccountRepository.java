package com.server.zelo.domain.user.repository;

import com.server.zelo.domain.user.entity.SocialAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {
}
