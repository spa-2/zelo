package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 실제 설정(5초, 30초)과 같은 구조를 짧은 시간으로 만들어, 시간 제한·재시도·서킷 브레이커의 동작을 빠르게 확인한다
@ExtendWith(MockitoExtension.class)
class ResilientLlmGatewayTest {

    private static final Duration TIMEOUT = Duration.ofMillis(200);
    private static final Duration OPEN_DURATION = Duration.ofMillis(300);
    private static final LlmReply REPLY = LlmReply.of("답변", "gpt-test", 10, 5);

    @Mock
    private LlmGateway delegate;

    private CircuitBreaker circuitBreaker;
    private ExecutorService executorService;
    private ResilientLlmGateway resilientLlmGateway;

    @BeforeEach
    void setUp() {
        Retry retry = Retry.of("test", RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .retryOnException(LlmErrorClassifier::isRetryable)
                .build());
        TimeLimiter timeLimiter = TimeLimiter.of("test", TimeLimiterConfig.custom()
                .timeoutDuration(TIMEOUT)
                .cancelRunningFuture(true)
                .build());
        circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(OPEN_DURATION)
                .permittedNumberOfCallsInHalfOpenState(1)
                .ignoreException(throwable -> !LlmErrorClassifier.isCircuitFailure(throwable))
                .build());
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        resilientLlmGateway = new ResilientLlmGateway(retry, delegate, timeLimiter, circuitBreaker, executorService);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void 제때_답하면_한_번만_호출하고_답을_그대로_돌려준다() {
        // given
        given(delegate.explain("system", "질문")).willReturn(REPLY);

        // when
        LlmReply reply = resilientLlmGateway.explain("system", "질문");

        // then
        assertThat(reply).isEqualTo(REPLY);
        then(delegate).should(times(1)).explain("system", "질문");
    }

    @Test
    void 시간_안에_답하지_않으면_한_번_재시도하고_그래도_늦으면_AI_TIMEOUT으로_실패한다() {
        // given
        given(delegate.explain(anyString(), anyString())).willAnswer(invocation -> {
            Thread.sleep(2_000);
            return REPLY;
        });
        long start = System.nanoTime();

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        then(delegate).should(times(2)).explain(anyString(), anyString());
        // 늦은 응답(2초)을 끝까지 기다리지 않고 시도마다 시간 제한에서 끊는다
        assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT.multipliedBy(2)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void 시간_제한에_걸린_호출은_끊어서_뒤에서_계속_돌지_않게_한다() throws InterruptedException {
        // given
        CountDownLatch interrupted = new CountDownLatch(2);
        given(delegate.explain(anyString(), anyString())).willAnswer(invocation -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return REPLY;
        });

        // when
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", "질문"))
                .isInstanceOf(BusinessException.class);

        // then
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void 첫_시도가_서버_오류여도_재시도가_성공하면_답을_돌려준다() {
        // given
        given(delegate.explain(anyString(), anyString()))
                .willThrow(OpenAiErrors.serverError(503))
                .willReturn(REPLY);

        // when
        LlmReply reply = resilientLlmGateway.explain("system", "질문");

        // then
        assertThat(reply).isEqualTo(REPLY);
        then(delegate).should(times(2)).explain(anyString(), anyString());
    }

    @Test
    void 요청_한도_초과가_계속되면_한_번_재시도하고_AI_RATE_LIMITED로_실패한다() {
        // given
        given(delegate.explain(anyString(), anyString())).willThrow(OpenAiErrors.rateLimit());

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_RATE_LIMITED));
        then(delegate).should(times(2)).explain(anyString(), anyString());
    }

    @Test
    void 요청_오류_4xx는_재시도하지_않고_AI_REQUEST_REJECTED로_실패한다() {
        // given
        given(delegate.explain(anyString(), anyString())).willThrow(OpenAiErrors.badRequest());

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_REQUEST_REJECTED));
        then(delegate).should(times(1)).explain(anyString(), anyString());
    }

    @Test
    void 요청_오류_4xx가_반복돼도_서킷은_열리지_않아_다음_정상_요청은_성공한다() {
        // given: 최소 시도 수(4)를 넘게 4xx를 받는다
        given(delegate.explain(anyString(), anyString())).willThrow(OpenAiErrors.badRequest());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> resilientLlmGateway.explain("system", "질문"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_REQUEST_REJECTED));
        }
        willReturn(REPLY).given(delegate).explain(anyString(), anyString());

        // when
        LlmReply reply = resilientLlmGateway.explain("system", "질문");

        // then: 4xx는 장부에 적히지 않아 성공 1번만 남는다
        assertThat(reply).isEqualTo(REPLY);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
    }

    @Test
    void 빈_응답은_재시도하지_않고_AI_EMPTY_RESPONSE로_실패한다() {
        // given
        given(delegate.explain(anyString(), anyString())).willReturn(LlmReply.of(" ", "gpt-test", 10, 0));

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_EMPTY_RESPONSE));
        then(delegate).should(times(1)).explain(anyString(), anyString());
    }

    @Test
    void 실패가_쌓이면_서킷이_열려_LLM을_부르지_않고_바로_AI_CIRCUIT_OPEN으로_실패한다() {
        // given: 메시지 하나가 재시도까지 실패하면 2번으로 세므로, 2개 실패로 최소 시도 수(4)가 찬다
        given(delegate.explain(anyString(), anyString())).willThrow(OpenAiErrors.serverError(500));
        failTwice();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_CIRCUIT_OPEN));
        then(delegate).should(times(4)).explain(anyString(), anyString());
    }

    @Test
    void 열린_시간이_지나면_시험_호출을_보내고_성공하면_서킷이_닫힌다() throws InterruptedException {
        // given
        given(delegate.explain(anyString(), anyString())).willThrow(OpenAiErrors.serverError(500));
        failTwice();
        // 이미 예외를 던지도록 정한 mock은 given(...) 안에서 호출되면 예외가 나므로 will...given 형태로 다시 정한다
        willReturn(REPLY).given(delegate).explain(anyString(), anyString());
        Thread.sleep(OPEN_DURATION.plusMillis(100));

        // when
        LlmReply reply = resilientLlmGateway.explain("system", "질문");

        // then
        assertThat(reply).isEqualTo(REPLY);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 서킷이_열려_있는_동안에는_감싼_구현을_한_번도_부르지_않는다() {
        // given
        circuitBreaker.transitionToOpenState();

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", "질문"))
                .isInstanceOf(BusinessException.class);
        then(delegate).should(never()).explain(anyString(), anyString());
    }

    private void failTwice() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> resilientLlmGateway.explain("system", "질문"))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
