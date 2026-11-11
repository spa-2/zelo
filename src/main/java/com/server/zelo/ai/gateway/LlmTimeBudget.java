package com.server.zelo.ai.gateway;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 채팅 메시지 하나를 답하는 동안 LLM을 몇 번 부르든 정해진 시간 안에 끝나게 하는 마감 시각.
// 시간 제한(zelo.llm.timeout)과 재시도는 호출 한 건 단위라, 메시지 하나가 LLM을 2~3번 부르면 최악의 대기가 그만큼 곱해진다.
// 마감 시각은 run을 부른 요청 스레드에만 둔다. ResilientLlmGateway는 시도를 다른 스레드에서 실행하지만 기다리기와 재시도는 요청 스레드에서 하므로, 여기서 남은 시간을 읽을 수 있다.
// LlmGateway 메서드에 마감 시각을 넘기지 않는 이유: 메시지 밖에서 부르는 호출(리뷰 요약)과 real/fake 구현까지 모두 바꿔야 해서다
@Component
public class LlmTimeBudget {

    // System.nanoTime() 기준 마감 시각. 벽시계(Instant)는 시간 동기화로 앞뒤로 움직일 수 있어 쓰지 않는다
    private final ThreadLocal<Long> deadline = new ThreadLocal<>();
    private final Duration budget;
    private final Duration minAttempt;

    public LlmTimeBudget(@Value("${zelo.llm.time-budget.total}") Duration budget,
                         @Value("${zelo.llm.time-budget.min-attempt}") Duration minAttempt) {
        if (minAttempt.isNegative() || budget.compareTo(minAttempt) <= 0) {
            throw new IllegalArgumentException("시간 예산은 시도 최소 시간보다 길어야 합니다: budget=" + budget + ", minAttempt=" + minAttempt);
        }
        this.budget = budget;
        this.minAttempt = minAttempt;
    }

    // work 안의 LLM 호출이 모두 같은 마감 시각을 나눠 쓴다. 요청 스레드는 재사용되므로 끝나면 반드시 지운다.
    // 이미 마감 안에서 다시 부르면 바깥 마감을 늘리지 않는다
    public <T> T run(Supplier<T> work) {
        Long outer = deadline.get();
        long candidate = System.nanoTime() + budget.toNanos();
        deadline.set(outer == null ? candidate : Math.min(outer, candidate));
        try {
            return work.get();
        } finally {
            if (outer == null) {
                deadline.remove();
            } else {
                deadline.set(outer);
            }
        }
    }

    // 이번 시도에 줄 시간: 시도당 제한과 남은 시간 중 짧은 쪽이다.
    // 남은 시간이 시도 최소 시간보다 적으면 빈 값이다. 그 시간으로는 답을 받기 어려워 시도하지 않는다.
    // 마감이 없으면(run 밖의 호출) 시도당 제한을 그대로 쓴다
    public Optional<Duration> attemptTimeout(Duration limit) {
        Optional<Duration> remaining = remaining();
        if (remaining.isEmpty()) {
            return Optional.of(limit);
        }
        if (remaining.get().compareTo(minAttempt) < 0) {
            return Optional.empty();
        }
        return Optional.of(remaining.get().compareTo(limit) < 0 ? remaining.get() : limit);
    }

    // 재시도 대기(wait)를 마친 뒤에도 시도 최소 시간이 남는지 본다. 남지 않으면 기다려 봐야 시도하지 못한다
    public boolean allowsRetry(Duration wait) {
        return remaining().map(left -> left.minus(wait).compareTo(minAttempt) >= 0).orElse(true);
    }

    Optional<Duration> remaining() {
        Long end = deadline.get();
        if (end == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(Math.max(0, end - System.nanoTime())));
    }

    // 메시지의 시간 예산 때문에 시도하지 못했거나 짧게 끊은 시도. 응답 코드는 시간 초과(AI_002)와 같다.
    // OpenAI가 느리다는 증거가 아니라 우리 예산이 바닥난 것이라 서킷 브레이커 실패로 세지 않는다(LlmErrorClassifier)
    public static final class ExceededException extends RuntimeException {

        public ExceededException(String message) {
            super(message, null, false, false);
        }
    }
}
