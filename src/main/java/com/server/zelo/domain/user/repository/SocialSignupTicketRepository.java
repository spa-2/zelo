package com.server.zelo.domain.user.repository;

import com.server.zelo.common.util.HashUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SocialSignupTicketRepository {

    // 30분 정도는 여유를 줘서 회원가입을 충분히 할 수 있게 해줌
    private static final int TICKETS_TIME = 30;
    private static final String SOCIAL_SIGNUP_TICKETS_PREFIX = "social:signup:";

    private final HashUtil hashUtil;
    private final StringRedisTemplate stringRedisTemplate;

    public void save(String socialSignupTicket, String ProfileJson) {
        String hashedTicket = hash(socialSignupTicket);
        stringRedisTemplate.opsForValue().set(SOCIAL_SIGNUP_TICKETS_PREFIX + hashedTicket,
                ProfileJson,  Duration.ofMinutes(TICKETS_TIME));
    }

    public Optional<String> consume(String socialSignupTicket) {
        String hashedTicket = hash(socialSignupTicket);
        // 값을 지우면서 꺼내어서 같은 번호표로 2번 이상 소비할 수 없음
        String profileJson = stringRedisTemplate.opsForValue().getAndDelete(SOCIAL_SIGNUP_TICKETS_PREFIX + hashedTicket);

        return Optional.ofNullable(profileJson);
    }

    private String hash(String socialSignupTicket) {
        return hashUtil.hash(socialSignupTicket);
    }
}
