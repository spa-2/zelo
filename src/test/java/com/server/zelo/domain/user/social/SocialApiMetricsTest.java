package com.server.zelo.domain.user.social;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.user.entity.SocialProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialApiMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SocialApiMetrics socialApiMetrics = new SocialApiMetrics(new BusinessMetrics(meterRegistry));

    @Test
    void 성공하면_결과를_그대로_돌려주고_SUCCESS로_기록한다() {
        // when
        String result = socialApiMetrics.measure(SocialProvider.GOOGLE, "TOKEN", () -> "토큰");

        // then
        assertThat(result).isEqualTo("토큰");
        assertThat(timerCount("GOOGLE", "TOKEN", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void 비즈니스_예외는_같은_예외를_다시_던지고_에러_코드로_기록한다() {
        // given
        BusinessException exception = new BusinessException(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE);

        // when & then 예외를 삼키면 기존 catch가 만든 응답이 바뀜
        assertThatThrownBy(() -> socialApiMetrics.measure(SocialProvider.KAKAO, "USER_INFO", () -> {
            throw exception;
        })).isSameAs(exception);
        assertThat(timerCount("KAKAO", "USER_INFO", ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE.getCode())).isEqualTo(1);
    }

    @Test
    void 그_밖의_예외는_UNKNOWN으로_기록하고_다시_던진다() {
        // given
        IllegalStateException exception = new IllegalStateException("예상 못 한 오류");

        // when & then
        assertThatThrownBy(() -> socialApiMetrics.measure(SocialProvider.GOOGLE, "TOKEN", () -> {
            throw exception;
        })).isSameAs(exception);
        assertThat(timerCount("GOOGLE", "TOKEN", "UNKNOWN")).isEqualTo(1);
    }

    private long timerCount(String provider, String operation, String outcome) {
        return meterRegistry.get(BusinessMetric.SOCIAL_API_CALL.getKey())
                .tags("provider", provider, "operation", operation, "outcome", outcome)
                .timer().count();
    }
}
