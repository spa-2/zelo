package com.server.zelo.global.config;

import com.server.zelo.ai.gateway.LlmErrorClassifier;
import com.server.zelo.common.monitoring.LogContextInterceptor;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// ResilientLlmGateway가 쓰는 부품을 빈으로 둔다. 테스트에서 서킷 상태를 꺼내 보거나 초기화할 수 있게 하기 위해서다
@Slf4j
@Configuration
public class LlmResilienceConfig {

    private static final String NAME = "llm";

    @Bean
    public TimeLimiter llmTimeLimiter(@Value("${zelo.llm.timeout}") Duration timeout) {
        return TimeLimiter.of(NAME, TimeLimiterConfig.custom()
                .timeoutDuration(timeout)
                // 기다리기를 포기한 호출은 끊어서(interrupt) 뒤에서 계속 돌지 않게 한다
                .cancelRunningFuture(true)
                .build());
    }

    @Bean
    public Retry llmRetry(
            @Value("${zelo.llm.retry.max-attempts}") int maxAttempts,
            @Value("${zelo.llm.retry.wait-duration}") Duration waitDuration) {
        Retry retry = Retry.of(NAME, RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(waitDuration)
                .retryOnException(LlmErrorClassifier::isRetryable)
                .build());
        retry.getEventPublisher().onRetry(event -> log.warn("LLM 재시도 {}회차: {}",
                event.getNumberOfRetryAttempts(), LlmErrorClassifier.classify(event.getLastThrowable()).getCode()));
        return retry;
    }

    @Bean
    public CircuitBreaker llmCircuitBreaker(
            @Value("${zelo.llm.circuit-breaker.sliding-window-size}") int slidingWindowSize,
            @Value("${zelo.llm.circuit-breaker.minimum-number-of-calls}") int minimumNumberOfCalls,
            @Value("${zelo.llm.circuit-breaker.failure-rate-threshold}") float failureRateThreshold,
            @Value("${zelo.llm.circuit-breaker.wait-duration-in-open-state}") Duration waitDurationInOpenState,
            @Value("${zelo.llm.circuit-breaker.permitted-number-of-calls-in-half-open-state}") int permittedCalls) {
        CircuitBreaker circuitBreaker = CircuitBreaker.of(NAME, CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED) // "최근 몇 번"을 기준
                .slidingWindowSize(slidingWindowSize) // 최근 10번의 시도
                .minimumNumberOfCalls(minimumNumberOfCalls) // 최소 5번은 쌓여야 판단
                .failureRateThreshold(failureRateThreshold) // 그중 50% 이상 실패하면 차단
                .waitDurationInOpenState(waitDurationInOpenState) // 30초 동안 차단 유지
                .permittedNumberOfCallsInHalfOpenState(permittedCalls) // 30초 뒤 시험 삼아 3번 보냄
                .ignoreException(throwable -> !LlmErrorClassifier.isCircuitFailure(throwable)) // 4xx 요청 거절은 장부에 적지 않음
                .build());
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("LLM 서킷 상태 변경: {}", event.getStateTransition()));
        return circuitBreaker;
    }

    // 시도마다 별도 스레드에서 실행해야 시간 제한을 걸 수 있다. 대부분 응답을 기다리는 시간이라 가상 스레드를 쓴다.
    // 스레드가 바뀌면 요청 스레드의 traceId·MDC가 따라오지 않아, 그 안의 로그(Spring AI 요청·응답, 오류)가 어느 요청인지 모르게 된다.
    // 그래서 작업을 넘길 때 요청 스레드의 컨텍스트(trace, MDC의 userId·roomId)를 복사해 가게 감싼다
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService llmExecutorService() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                new Slf4jThreadLocalAccessor(LogContextInterceptor.USER_ID, LogContextInterceptor.ROOM_ID));
        return ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(),
                ContextSnapshotFactory.builder().build());
    }
}
