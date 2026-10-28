package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.LoginLockedException;
import com.server.zelo.domain.user.dto.login.AttemptResult;
import com.server.zelo.domain.user.repository.LoginAttemptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    private static final String EMAIL = "jieun@example.com";
    private static final String IP = "203.0.113.7";

    @InjectMocks
    private LoginAttemptService loginAttemptService;

    @Mock
    private LoginAttemptRepository loginAttemptRepository;

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void 시도_횟수가_5회_이하이면_통과한다(int count) {
        // given
        givenAttemptCount(count);

        // when & then
        assertThatCode(() -> loginAttemptService.attempt(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    void 시도_횟수가_5회를_넘으면_남은_시간과_함께_잠금_예외가_발생한다() {
        // given
        given(loginAttemptRepository.increment(EMAIL, IP, 5, Duration.ofMinutes(10), Duration.ofMinutes(5)))
                .willReturn(new AttemptResult(6, 540));

        // when & then
        assertThatThrownBy(() -> loginAttemptService.attempt(EMAIL, IP))
                .isInstanceOf(LoginLockedException.class)
                .satisfies(e -> {
                    LoginLockedException locked = (LoginLockedException) e;
                    assertThat(locked.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
                    assertThat(locked.getRetryAfterSeconds()).isEqualTo(540L);
                });
    }

    @Test
    void 잠긴_뒤에도_계속_시도하면_계속_거절한다() {
        // given
        givenAttemptCount(30);

        // when & then
        assertThatThrownBy(() -> loginAttemptService.attempt(EMAIL, IP)).isInstanceOf(LoginLockedException.class);
    }

    // 합의한 정책(5회까지 허용, 초과하면 10분 잠금, 횟수는 5분 동안 유지)이 바뀌면 의도한 변경인지 알 수 있게 값을 고정함
    @Test
    void 정책은_5회_허용_잠금_10분_윈도우_5분으로_저장소에_전달한다() {
        // given
        givenAttemptCount(1);

        // when
        loginAttemptService.attempt(EMAIL, IP);

        // then
        then(loginAttemptRepository).should().increment(EMAIL, IP, 5, Duration.ofMinutes(10), Duration.ofMinutes(5));
    }

    @Test
    void 시도_횟수_삭제는_저장소에_위임한다() {
        // when
        loginAttemptService.deleteAttempt(EMAIL, IP);

        // then
        then(loginAttemptRepository).should().deleteLoginAttempt(EMAIL, IP);
    }

    private void givenAttemptCount(int count) {
        given(loginAttemptRepository.increment(EMAIL, IP, 5, Duration.ofMinutes(10), Duration.ofMinutes(5)))
                .willReturn(new AttemptResult(count, 300));
    }
}
