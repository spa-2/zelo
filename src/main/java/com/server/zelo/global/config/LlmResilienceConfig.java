package com.server.zelo.global.config;

import com.server.zelo.ai.gateway.LlmErrorClassifier;
import com.server.zelo.ai.gateway.LlmTimeBudget;
import com.server.zelo.common.monitoring.LogContextInterceptor;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import io.micrometer.core.instrument.binder.MeterBinder;
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

    // 세 부품을 레지스트리에서 만든다. resilience4j-micrometer가 레지스트리 단위로만 지표를 붙일 수 있기 때문이다
    @Bean
    public TimeLimiterRegistry llmTimeLimiterRegistry(@Value("${zelo.llm.timeout}") Duration timeout) {
        return TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(timeout)
                // 기다리기를 포기한 호출은 끊어서(interrupt) 뒤에서 계속 돌지 않게 한다
                .cancelRunningFuture(true)
                .build());
    }

    @Bean
    public TimeLimiter llmTimeLimiter(TimeLimiterRegistry llmTimeLimiterRegistry) {
        return llmTimeLimiterRegistry.timeLimiter(NAME);
    }

    // 다시 보내면 나을 수 있는 오류라도, 채팅 메시지에 남은 시간이 재시도 대기 뒤 시도 최소 시간보다 적으면 재시도하지 않는다.
    // 그래서 시간이 없어 실패한 호출은 시간 초과가 아니라 실제로 난 오류(429, 5xx 등)로 응답한다
    @Bean
    public RetryRegistry llmRetryRegistry(
            @Value("${zelo.llm.retry.max-attempts}") int maxAttempts,
            @Value("${zelo.llm.retry.wait-duration}") Duration waitDuration,
            LlmTimeBudget llmTimeBudget) {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(waitDuration)
                .retryOnException(throwable -> LlmErrorClassifier.isRetryable(throwable)
                        && llmTimeBudget.allowsRetry(waitDuration))
                .build());
    }

    @Bean
    public Retry llmRetry(RetryRegistry llmRetryRegistry) {
        Retry retry = llmRetryRegistry.retry(NAME);
        retry.getEventPublisher().onRetry(event -> log.warn("LLM 재시도 {}회차: {}",
                event.getNumberOfRetryAttempts(), LlmErrorClassifier.classify(event.getLastThrowable()).getCode()));
        return retry;
    }

    @Bean
    public CircuitBreakerRegistry llmCircuitBreakerRegistry(
            @Value("${zelo.llm.circuit-breaker.sliding-window-size}") int slidingWindowSize,
            @Value("${zelo.llm.circuit-breaker.minimum-number-of-calls}") int minimumNumberOfCalls,
            @Value("${zelo.llm.circuit-breaker.failure-rate-threshold}") float failureRateThreshold,
            @Value("${zelo.llm.circuit-breaker.wait-duration-in-open-state}") Duration waitDurationInOpenState,
            @Value("${zelo.llm.circuit-breaker.permitted-number-of-calls-in-half-open-state}") int permittedCalls) {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED) // "최근 몇 번"을 기준
                .slidingWindowSize(slidingWindowSize) // 최근 10번의 시도
                .minimumNumberOfCalls(minimumNumberOfCalls) // 최소 5번은 쌓여야 판단
                .failureRateThreshold(failureRateThreshold) // 그중 50% 이상 실패하면 차단
                .waitDurationInOpenState(waitDurationInOpenState) // 30초 동안 차단 유지
                .permittedNumberOfCallsInHalfOpenState(permittedCalls) // 30초 뒤 시험 삼아 3번 보냄
                .ignoreException(throwable -> !LlmErrorClassifier.isCircuitFailure(throwable)) // 4xx 요청 거절은 장부에 적지 않음
                .build());
    }

    @Bean
    public CircuitBreaker llmCircuitBreaker(CircuitBreakerRegistry llmCircuitBreakerRegistry) {
        CircuitBreaker circuitBreaker = llmCircuitBreakerRegistry.circuitBreaker(NAME);
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("LLM 서킷 상태 변경: {}", event.getStateTransition()));
        return circuitBreaker;
    }

    // 서킷 상태·실패율·막힌 호출 수, 재시도 결과, 시간 초과 수를 resilience4j.* 지표로 내보낸다 (name=llm 태그)
    @Bean
    public MeterBinder llmResilienceMetrics(CircuitBreakerRegistry llmCircuitBreakerRegistry,
                                            RetryRegistry llmRetryRegistry,
                                            TimeLimiterRegistry llmTimeLimiterRegistry) {
        return meterRegistry -> {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(llmCircuitBreakerRegistry).bindTo(meterRegistry);
            TaggedRetryMetrics.ofRetryRegistry(llmRetryRegistry).bindTo(meterRegistry);
            TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(llmTimeLimiterRegistry).bindTo(meterRegistry);
        };
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
