package com.server.zelo.domain.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SocialSignupTicketRepository {

    private final StringRedisTemplate stringRedisTemplate;
}
