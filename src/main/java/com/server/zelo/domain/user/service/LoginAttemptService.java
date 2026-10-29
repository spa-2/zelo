package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.login.AttemptResult;
import com.server.zelo.common.exception.RateLimitExceededException;
import com.server.zelo.domain.user.repository.LoginAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5; // 최대 시도 횟수
    private static final Duration LOCK = Duration.ofMinutes(10); // 횟수 초과 시 제한 시간
    private static final Duration WINDOW = Duration.ofMinutes(5); // 로그인 시도 시 저장할 시간

    private final LoginAttemptRepository loginAttemptRepository;

    public void attempt(String email, String remoteAddr) {
        AttemptResult result = loginAttemptRepository.increment(email, remoteAddr,MAX_ATTEMPTS, LOCK, WINDOW);
        if (result.count() > MAX_ATTEMPTS){
            throw new RateLimitExceededException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS, result.retryAfterSeconds());
        }
    }

    public void deleteAttempt(String email, String remoteAddr) {
        loginAttemptRepository.deleteLoginAttempt(email, remoteAddr);
    }
}
