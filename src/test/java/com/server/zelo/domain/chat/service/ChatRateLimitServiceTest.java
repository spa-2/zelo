package com.server.zelo.domain.chat.service;

import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.RateLimitExceededException;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.chat.repository.ChatRateLimitRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ChatRateLimitServiceTest {

    private static final Long USER_ID = 1L;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);

    @Mock
    private ChatRateLimitRepository chatRateLimitRepository;

    @InjectMocks
    private ChatRateLimitService chatRateLimitService;

    @ParameterizedTest
    @ValueSource(longs = {1, 5, 10})
    void 일분에_10번까지는_통과한다(long count) {
        // given
        given(chatRateLimitRepository.increaseCount(USER_ID, WINDOW)).willReturn(count);

        // when & then
        assertThatCode(() -> chatRateLimitService.validateLimit(USER_ID)).doesNotThrowAnyException();
        then(chatRateLimitRepository).should(never()).getRemainingSeconds(USER_ID);
        assertThat(meterRegistry.find("zelo.chat.rate.limited").counter()).isNull();
    }

    @Test
    void 일분에_10번을_넘으면_남은_시간과_함께_429_예외가_발생한다() {
        // given
        given(chatRateLimitRepository.increaseCount(USER_ID, WINDOW)).willReturn(11L);
        given(chatRateLimitRepository.getRemainingSeconds(USER_ID)).willReturn(15L);

        // when & then
        assertThatThrownBy(() -> chatRateLimitService.validateLimit(USER_ID))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHAT_RATE_LIMITED);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(15L);
                });
        assertThat(meterRegistry.get("zelo.chat.rate.limited").counter().count()).isEqualTo(1);
    }
}
