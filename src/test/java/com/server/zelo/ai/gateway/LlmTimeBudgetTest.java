package com.server.zelo.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

// 시간은 흐르기만 하므로, 남은 시간은 "예산보다 많지 않다"와 "어느 값보다 적다"처럼 한쪽으로만 확인한다
class LlmTimeBudgetTest {

    private static final Duration LIMIT = Duration.ofHours(1);

    private final LlmTimeBudget llmTimeBudget = new LlmTimeBudget(Duration.ofSeconds(10), Duration.ofSeconds(3));

    @Test
    void 마감_밖의_호출은_시도당_제한을_그대로_받는다() {
        // when
        Optional<Duration> attemptTimeout = llmTimeBudget.attemptTimeout(LIMIT);

        // then
        assertThat(attemptTimeout).contains(LIMIT);
    }

    @Test
    void 남은_시간이_시도당_제한보다_짧으면_남은_시간만_받는다() {
        // when
        Optional<Duration> attemptTimeout = llmTimeBudget.run(() -> llmTimeBudget.attemptTimeout(LIMIT));

        // then
        assertThat(attemptTimeout).hasValueSatisfying(timeout -> assertThat(timeout)
                .isLessThanOrEqualTo(Duration.ofSeconds(10))
                .isGreaterThan(Duration.ofSeconds(9)));
    }

    @Test
    void 남은_시간이_시도당_제한보다_길면_시도당_제한을_받는다() {
        // given
        Duration shortLimit = Duration.ofSeconds(5);

        // when
        Optional<Duration> attemptTimeout = llmTimeBudget.run(() -> llmTimeBudget.attemptTimeout(shortLimit));

        // then
        assertThat(attemptTimeout).contains(shortLimit);
    }

    @Test
    void 남은_시간이_시도_최소_시간보다_적으면_시도하지_않도록_빈_값을_준다() {
        // given
        LlmTimeBudget shortBudget = new LlmTimeBudget(Duration.ofMillis(100), Duration.ofMillis(50));

        // when
        Optional<Duration> attemptTimeout = shortBudget.run(() -> {
            sleep(Duration.ofMillis(80));
            return shortBudget.attemptTimeout(LIMIT);
        });

        // then
        assertThat(attemptTimeout).isEmpty();
    }

    @Test
    void 재시도_대기_뒤에도_시도_최소_시간이_남으면_재시도를_허용한다() {
        // when & then
        assertThat(llmTimeBudget.run(() -> llmTimeBudget.allowsRetry(Duration.ofSeconds(1)))).isTrue();
    }

    @Test
    void 재시도_대기_뒤에_시도_최소_시간이_남지_않으면_재시도하지_않는다() {
        // when & then: 10초 - 8초 = 2초 < 시도 최소 시간 3초
        assertThat(llmTimeBudget.run(() -> llmTimeBudget.allowsRetry(Duration.ofSeconds(8)))).isFalse();
    }

    @Test
    void 마감_밖의_호출은_재시도를_막지_않는다() {
        // when & then
        assertThat(llmTimeBudget.allowsRetry(Duration.ofHours(1))).isTrue();
    }

    @Test
    void 끝나면_마감을_지워_재사용되는_요청_스레드의_다음_요청에_남기지_않는다() {
        // given
        llmTimeBudget.run(() -> "답변");

        // when & then
        assertThat(llmTimeBudget.remaining()).isEmpty();
    }

    @Test
    void 실패해도_마감을_지운다() {
        // when
        assertThatThrownBy(() -> llmTimeBudget.run(() -> {
            throw new IllegalStateException("실패");
        })).isInstanceOf(IllegalStateException.class);

        // then
        assertThat(llmTimeBudget.remaining()).isEmpty();
    }

    @Test
    void 마감_안에서_다시_불러도_바깥_마감을_늘리지_않고_끝나면_바깥_마감으로_돌아간다() {
        // given
        LlmTimeBudget shortBudget = new LlmTimeBudget(Duration.ofMillis(300), Duration.ofMillis(50));

        // when
        Duration[] remaining = shortBudget.run(() -> {
            sleep(Duration.ofMillis(150));
            Duration inner = shortBudget.run(() -> shortBudget.remaining().orElseThrow());
            return new Duration[]{inner, shortBudget.remaining().orElseThrow()};
        });

        // then: 안쪽에서 새로 300ms를 받지 않고 바깥에 남은 150ms 이하를 그대로 쓴다
        assertThat(remaining[0]).isLessThanOrEqualTo(Duration.ofMillis(150));
        assertThat(remaining[1]).isLessThanOrEqualTo(remaining[0]);
    }

    @Test
    void 마감은_정한_요청_스레드에만_있다() {
        // when
        Optional<Duration> otherThread = llmTimeBudget.run(
                () -> CompletableFuture.supplyAsync(llmTimeBudget::remaining).join());

        // then
        assertThat(otherThread).isEmpty();
    }

    @Test
    void 시간_예산이_시도_최소_시간보다_길지_않으면_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> new LlmTimeBudget(Duration.ofSeconds(3), Duration.ofSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
