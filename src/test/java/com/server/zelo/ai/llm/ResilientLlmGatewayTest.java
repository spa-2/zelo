package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Keyword;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

// 실제 설정(5초, 30초)과 같은 구조를 짧은 시간으로 만들어, 시간 제한·재시도·서킷 브레이커의 동작을 빠르게 확인한다
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ResilientLlmGatewayTest {

    // 테스트에서 쓰는 재료만 담은 재료 목록 (실제로는 재료 테이블에서 읽는다)
    private static final Map<String, BaseUnit> INGREDIENT_UNITS = Map.of(
            "김치", BaseUnit.G, "밥", BaseUnit.G, "계란", BaseUnit.EA, "대파", BaseUnit.BUNCH);

    private static final Duration TIMEOUT = Duration.ofMillis(200);
    private static final Duration OPEN_DURATION = Duration.ofMillis(300);
    private static final LlmReply REPLY = LlmReply.of("답변", "gpt-test", 10, 5);
    // 실패해서 응답이 없을 때 로그에 남는 설정 모델 이름 (성공 응답의 모델과 구분하려고 다른 이름을 쓴다)
    private static final String CONFIGURED_MODEL = "gpt-configured";
    private static final String REVIEW_SUMMARY_MODEL = "gpt-review-configured";
    private static final String ATTEMPT_LOG = "LLM 호출 attempt=";

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
        resilientLlmGateway = new ResilientLlmGateway(retry, CONFIGURED_MODEL, REVIEW_SUMMARY_MODEL, delegate, timeLimiter,
                circuitBreaker, executorService);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void 제때_답하면_한_번만_호출하고_답을_그대로_돌려준다() {
        // given
        given(delegate.explain("system", List.of(), "질문")).willReturn(REPLY);

        // when
        LlmReply reply = resilientLlmGateway.explain("system", List.of(), "질문");

        // then
        assertThat(reply).isEqualTo(REPLY);
        then(delegate).should(times(1)).explain("system", List.of(), "질문");
    }

    @Test
    void 시간_안에_답하지_않으면_한_번_재시도하고_그래도_늦으면_AI_TIMEOUT으로_실패한다() {
        // given
        given(delegate.explain(anyString(), anyList(), anyString())).willAnswer(invocation -> {
            Thread.sleep(2_000);
            return REPLY;
        });
        long start = System.nanoTime();

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        then(delegate).should(times(2)).explain(anyString(), anyList(), anyString());
        // 늦은 응답(2초)을 끝까지 기다리지 않고 시도마다 시간 제한에서 끊는다
        assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT.multipliedBy(2)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void 시간_제한에_걸린_호출은_끊어서_뒤에서_계속_돌지_않게_한다() throws InterruptedException {
        // given
        CountDownLatch interrupted = new CountDownLatch(2);
        given(delegate.explain(anyString(), anyList(), anyString())).willAnswer(invocation -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return REPLY;
        });

        // when
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOf(BusinessException.class);

        // then
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void 첫_시도가_서버_오류여도_재시도가_성공하면_답을_돌려준다() {
        // given
        given(delegate.explain(anyString(), anyList(), anyString()))
                .willThrow(OpenAiErrors.serverError(503))
                .willReturn(REPLY);

        // when
        LlmReply reply = resilientLlmGateway.explain("system", List.of(), "질문");

        // then
        assertThat(reply).isEqualTo(REPLY);
        then(delegate).should(times(2)).explain(anyString(), anyList(), anyString());
    }

    @Test
    void 요청_한도_초과가_계속되면_한_번_재시도하고_AI_RATE_LIMITED로_실패한다() {
        // given
        given(delegate.explain(anyString(), anyList(), anyString())).willThrow(OpenAiErrors.rateLimit());

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_RATE_LIMITED));
        then(delegate).should(times(2)).explain(anyString(), anyList(), anyString());
    }

    @Test
    void 요청_오류_4xx는_재시도하지_않고_AI_REQUEST_REJECTED로_실패한다() {
        // given
        given(delegate.explain(anyString(), anyList(), anyString())).willThrow(OpenAiErrors.badRequest());

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_REQUEST_REJECTED));
        then(delegate).should(times(1)).explain(anyString(), anyList(), anyString());
    }

    @Test
    void 요청_오류_4xx가_반복돼도_서킷은_열리지_않아_다음_정상_요청은_성공한다() {
        // given: 최소 시도 수(4)를 넘게 4xx를 받는다
        given(delegate.explain(anyString(), anyList(), anyString())).willThrow(OpenAiErrors.badRequest());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_REQUEST_REJECTED));
        }
        willReturn(REPLY).given(delegate).explain(anyString(), anyList(), anyString());

        // when
        LlmReply reply = resilientLlmGateway.explain("system", List.of(), "질문");

        // then: 4xx는 장부에 적히지 않아 성공 1번만 남는다
        assertThat(reply).isEqualTo(REPLY);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
    }

    @Test
    void 빈_응답은_재시도하지_않고_AI_EMPTY_RESPONSE로_실패한다() {
        // given
        given(delegate.explain(anyString(), anyList(), anyString())).willReturn(LlmReply.of(" ", "gpt-test", 10, 0));

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_EMPTY_RESPONSE));
        then(delegate).should(times(1)).explain(anyString(), anyList(), anyString());
    }

    @Test
    void 실패가_쌓이면_서킷이_열려_LLM을_부르지_않고_바로_AI_CIRCUIT_OPEN으로_실패한다() {
        // given: 메시지 하나가 재시도까지 실패하면 2번으로 세므로, 2개 실패로 최소 시도 수(4)가 찬다
        given(delegate.explain(anyString(), anyList(), anyString())).willThrow(OpenAiErrors.serverError(500));
        failTwice();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_CIRCUIT_OPEN));
        then(delegate).should(times(4)).explain(anyString(), anyList(), anyString());
    }

    @Test
    void 열린_시간이_지나면_시험_호출을_보내고_성공하면_서킷이_닫힌다() throws InterruptedException {
        // given
        given(delegate.explain(anyString(), anyList(), anyString())).willThrow(OpenAiErrors.serverError(500));
        failTwice();
        // 이미 예외를 던지도록 정한 mock은 given(...) 안에서 호출되면 예외가 나므로 will...given 형태로 다시 정한다
        willReturn(REPLY).given(delegate).explain(anyString(), anyList(), anyString());
        Thread.sleep(OPEN_DURATION.plusMillis(100));

        // when
        LlmReply reply = resilientLlmGateway.explain("system", List.of(), "질문");

        // then
        assertThat(reply).isEqualTo(REPLY);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 서킷이_열려_있는_동안에는_감싼_구현을_한_번도_부르지_않는다() {
        // given
        circuitBreaker.transitionToOpenState();

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOf(BusinessException.class);
        then(delegate).should(never()).explain(anyString(), anyList(), anyString());
    }

    @Test
    void 성공하면_모델_응답_시간_토큰을_담은_성공_로그를_한_줄_남기고_원문은_남기지_않는다(CapturedOutput output) {
        // given
        given(delegate.explain("system prompt", List.of(), "김치볶음밥 재료")).willReturn(REPLY);

        // when
        resilientLlmGateway.explain("system prompt", List.of(), "김치볶음밥 재료");

        // then
        assertThat(attemptLogLines(output)).hasSize(1);
        assertThat(attemptLogLines(output).getFirst())
                .contains("LLM 호출 attempt=1 model=gpt-test latencyMs=")
                .contains("success=true error=- promptTokens=10 completionTokens=5");
        assertThat(output.getOut()).doesNotContain("김치볶음밥 재료").doesNotContain("system prompt");
    }

    @Test
    void 재시도까지_실패하면_시도마다_한_줄씩_설정_모델과_오류_코드를_남긴다(CapturedOutput output) {
        // given
        given(delegate.explain(anyString(), anyList(), anyString())).willThrow(OpenAiErrors.serverError(500));

        // when
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOf(BusinessException.class);

        // then
        assertThat(attemptLogLines(output)).satisfiesExactly(
                line -> assertThat(line).contains("attempt=1 model=gpt-configured")
                        .contains("success=false error=AI_004 promptTokens=- completionTokens=-"),
                line -> assertThat(line).contains("attempt=2 model=gpt-configured")
                        .contains("success=false error=AI_004"));
    }

    @Test
    void 첫_시도가_실패하고_재시도가_성공하면_실패_한_줄과_성공_한_줄이_남는다(CapturedOutput output) {
        // given
        given(delegate.explain(anyString(), anyList(), anyString()))
                .willThrow(OpenAiErrors.rateLimit())
                .willReturn(REPLY);

        // when
        resilientLlmGateway.explain("system", List.of(), "질문");

        // then
        assertThat(attemptLogLines(output)).satisfiesExactly(
                line -> assertThat(line).contains("attempt=1").contains("success=false error=AI_003"),
                line -> assertThat(line).contains("attempt=2").contains("success=true error=-"));
    }

    @Test
    void 요청_오류_4xx는_재시도하지_않아_로그도_한_줄만_남는다(CapturedOutput output) {
        // given
        given(delegate.explain(anyString(), anyList(), anyString())).willThrow(OpenAiErrors.badRequest());

        // when
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOf(BusinessException.class);

        // then
        assertThat(attemptLogLines(output)).singleElement()
                .satisfies(line -> assertThat(line).contains("attempt=1").contains("error=AI_006"));
        assertThat(output.getOut()).doesNotContain("LLM 호출 실패 원인");
    }

    @Test
    void 서킷에_막힌_호출도_LLM을_부르지_않은_채로_한_줄_남는다(CapturedOutput output) {
        // given
        circuitBreaker.transitionToOpenState();

        // when
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOf(BusinessException.class);

        // then
        assertThat(attemptLogLines(output)).singleElement()
                .satisfies(line -> assertThat(line).contains("attempt=1").contains("success=false error=AI_008"));
        then(delegate).should(never()).explain(anyString(), anyList(), anyString());
    }

    @Test
    void 알_수_없는_오류만_원인을_찾을_수_있게_스택을_남긴다(CapturedOutput output) {
        // given
        given(delegate.explain(anyString(), anyList(), anyString())).willThrow(new IllegalStateException("unexpected"));

        // when
        assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_FAILED));

        // then
        assertThat(attemptLogLines(output)).singleElement()
                .satisfies(line -> assertThat(line).contains("error=AI_001"));
        assertThat(output.getOut()).contains("LLM 호출 실패 원인: AI_001").contains("IllegalStateException: unexpected");
    }

    @Test
    void 메뉴_계획이_검증을_통과하면_한_번만_호출하고_그대로_돌려준다(CapturedOutput output) {
        // given
        given(delegate.planMenu("system", List.of(), "질문", INGREDIENT_UNITS)).willReturn(planResult(validPlan()));

        // when
        LlmResult<MenuPlan> result = resilientLlmGateway.planMenu("system", List.of(), "질문", INGREDIENT_UNITS);

        // then
        assertThat(result.content()).isEqualTo(validPlan());
        then(delegate).should(times(1)).planMenu("system", List.of(), "질문", INGREDIENT_UNITS);
        // 메뉴 계획 호출도 시도마다 한 줄 로그가 남는다
        assertThat(attemptLogLines(output)).singleElement()
                .satisfies(line -> assertThat(line).contains("attempt=1 model=gpt-test")
                        .contains("success=true").contains("promptTokens=30 completionTokens=20"));
    }

    @Test
    void 메뉴_계획이_검증에_실패하면_한_번_더_물어보고_그래도_실패하면_AI_INVALID_RESPONSE로_실패한다() {
        // given: 장보기 요청인데 재료가 없다
        given(delegate.planMenu(anyString(), anyList(), anyString(), anyMap())).willReturn(planResult(planWithoutIngredients()));

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.planMenu("system", List.of(), "질문", INGREDIENT_UNITS))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
        then(delegate).should(times(2)).planMenu(anyString(), anyList(), anyString(), anyMap());
    }

    @Test
    void 메뉴_계획_검증_실패가_반복돼도_서킷은_열리지_않아_다음_정상_요청은_성공한다() {
        // given: 메시지 하나가 재시도까지 2번 실패하므로, 3개면 최소 시도 수(4)를 넘는다
        given(delegate.planMenu(anyString(), anyList(), anyString(), anyMap())).willReturn(planResult(planWithoutIngredients()));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> resilientLlmGateway.planMenu("system", List.of(), "질문", INGREDIENT_UNITS))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
        }
        willReturn(REPLY).given(delegate).explain(anyString(), anyList(), anyString());

        // when: 다른 사용자의 채팅
        LlmReply reply = resilientLlmGateway.explain("system", List.of(), "질문");

        // then: 검증 실패는 장부에 적히지 않아 성공 1번만 남는다
        assertThat(reply).isEqualTo(REPLY);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
    }

    @Test
    void 메뉴_계획이_처음에_검증에_실패해도_다시_물어서_맞게_답하면_돌려준다() {
        // given
        given(delegate.planMenu(anyString(), anyList(), anyString(), anyMap()))
                .willReturn(planResult(planWithoutIngredients()))
                .willReturn(planResult(validPlan()));

        // when
        LlmResult<MenuPlan> result = resilientLlmGateway.planMenu("system", List.of(), "질문", INGREDIENT_UNITS);

        // then
        assertThat(result.content()).isEqualTo(validPlan());
        then(delegate).should(times(2)).planMenu(anyString(), anyList(), anyString(), anyMap());
    }

    @Test
    void 메뉴_계획의_재료_단위는_함께_받은_재료_목록으로_검증한다() {
        // given: 같은 답(김치 400G)이라도 재료 목록에서 김치가 EA면 단위가 틀린 답이다
        Map<String, BaseUnit> kimchiByEach = Map.of("김치", BaseUnit.EA);
        given(delegate.planMenu(anyString(), anyList(), anyString(), anyMap())).willReturn(planResult(validPlan()));

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.planMenu("system", List.of(), "질문", kimchiByEach))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
        then(delegate).should(times(2)).planMenu(anyString(), anyList(), anyString(), anyMap());
    }

    @Test
    void 메뉴_계획_JSON을_객체로_바꾸지_못하면_한_번_더_물어보고_AI_INVALID_RESPONSE로_실패한다() {
        // given
        given(delegate.planMenu(anyString(), anyList(), anyString(), anyMap())).willThrow(OpenAiErrors.invalidJson());

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.planMenu("system", List.of(), "질문", INGREDIENT_UNITS))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
        then(delegate).should(times(2)).planMenu(anyString(), anyList(), anyString(), anyMap());
    }

    @Test
    void 메뉴_계획_응답이_비어_있으면_재시도하지_않고_AI_EMPTY_RESPONSE로_실패한다() {
        // given
        given(delegate.planMenu(anyString(), anyList(), anyString(), anyMap())).willReturn(planResult(null));

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.planMenu("system", List.of(), "질문", INGREDIENT_UNITS))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_EMPTY_RESPONSE));
        then(delegate).should(times(1)).planMenu(anyString(), anyList(), anyString(), anyMap());
    }

    @Test
    void 메뉴_계획도_시간_안에_답하지_않으면_한_번_재시도하고_AI_TIMEOUT으로_실패한다() {
        // given
        given(delegate.planMenu(anyString(), anyList(), anyString(), anyMap())).willAnswer(invocation -> {
            Thread.sleep(2_000);
            return planResult(validPlan());
        });

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.planMenu("system", List.of(), "질문", INGREDIENT_UNITS))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT));
        then(delegate).should(times(2)).planMenu(anyString(), anyList(), anyString(), anyMap());
    }

    @Test
    void 리뷰_요약은_줄바꿈과_연속_공백을_한_칸으로_줄여_한_줄로_돌려준다(CapturedOutput output) {
        // given
        given(delegate.summarizeReviews("system", "리뷰")).willReturn(LlmReply.of(" 맵지 않고\n아삭하다는   평이 많아요 ", "gpt-test", 40, 15));

        // when
        LlmReply reply = resilientLlmGateway.summarizeReviews("system", "리뷰");

        // then
        assertThat(reply.text()).isEqualTo("맵지 않고 아삭하다는 평이 많아요");
        assertThat(reply.model()).isEqualTo("gpt-test");
        then(delegate).should(times(1)).summarizeReviews("system", "리뷰");
        // 리뷰 요약 호출도 시도마다 한 줄 로그가 남는다
        assertThat(attemptLogLines(output)).singleElement()
                .satisfies(line -> assertThat(line).contains("success=true").contains("promptTokens=40 completionTokens=15"));
    }

    @Test
    void 리뷰_요약은_200자까지_허용한다() {
        // given
        String summary = "가".repeat(ReviewSummaryPrompt.SUMMARY_MAX_LENGTH);
        given(delegate.summarizeReviews(anyString(), anyString())).willReturn(LlmReply.of(summary, "gpt-test", 1, 1));

        // when
        LlmReply reply = resilientLlmGateway.summarizeReviews("system", "리뷰");

        // then
        assertThat(reply.text()).isEqualTo(summary);
    }

    @Test
    void 리뷰_요약이_200자를_넘으면_한_번_더_물어보고_그래도_길면_AI_INVALID_RESPONSE로_실패한다() {
        // given
        given(delegate.summarizeReviews(anyString(), anyString()))
                .willReturn(LlmReply.of("가".repeat(ReviewSummaryPrompt.SUMMARY_MAX_LENGTH + 1), "gpt-test", 1, 1));

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.summarizeReviews("system", "리뷰"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
        then(delegate).should(times(2)).summarizeReviews(anyString(), anyString());
    }

    @Test
    void 리뷰_요약이_실패하면_로그에_리뷰_요약용으로_설정한_모델을_남긴다(CapturedOutput output) {
        // given
        given(delegate.summarizeReviews(anyString(), anyString())).willThrow(OpenAiErrors.serverError(500));

        // when
        assertThatThrownBy(() -> resilientLlmGateway.summarizeReviews("system", "리뷰"))
                .isInstanceOf(BusinessException.class);

        // then
        assertThat(attemptLogLines(output)).isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("model=gpt-review-configured"));
    }

    @Test
    void 리뷰_요약이_처음에_너무_길어도_다시_물어서_짧게_답하면_돌려준다() {
        // given
        given(delegate.summarizeReviews(anyString(), anyString()))
                .willReturn(LlmReply.of("가".repeat(ReviewSummaryPrompt.SUMMARY_MAX_LENGTH + 1), "gpt-test", 1, 1))
                .willReturn(LlmReply.of("아삭하다는 평이 많아요", "gpt-test", 1, 1));

        // when
        LlmReply reply = resilientLlmGateway.summarizeReviews("system", "리뷰");

        // then
        assertThat(reply.text()).isEqualTo("아삭하다는 평이 많아요");
        then(delegate).should(times(2)).summarizeReviews(anyString(), anyString());
    }

    @Test
    void 리뷰_요약이_비어_있으면_재시도하지_않고_AI_EMPTY_RESPONSE로_실패한다() {
        // given
        given(delegate.summarizeReviews(anyString(), anyString())).willReturn(LlmReply.of(" \n ", "gpt-test", 1, 0));

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.summarizeReviews("system", "리뷰"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_EMPTY_RESPONSE));
        then(delegate).should(times(1)).summarizeReviews(anyString(), anyString());
    }

    @Test
    void 리뷰_요약도_시간_안에_답하지_않으면_한_번_재시도하고_AI_TIMEOUT으로_실패한다() {
        // given
        given(delegate.summarizeReviews(anyString(), anyString())).willAnswer(invocation -> {
            Thread.sleep(2_000);
            return REPLY;
        });

        // when & then
        assertThatThrownBy(() -> resilientLlmGateway.summarizeReviews("system", "리뷰"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT));
        then(delegate).should(times(2)).summarizeReviews(anyString(), anyString());
    }

    private static LlmResult<MenuPlan> planResult(MenuPlan menuPlan) {
        return LlmResult.of(menuPlan, "gpt-test", 30, 20);
    }

    private static MenuPlan validPlan() {
        return MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 4, null,
                List.of(MenuPlanIngredient.of("김치", 400, BaseUnit.G)), List.of(Keyword.NOT_SPICY), List.of());
    }

    private static MenuPlan planWithoutIngredients() {
        return MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, "김치볶음밥", 4, null, List.of(), List.of(), List.of());
    }

    private List<String> attemptLogLines(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains(ATTEMPT_LOG)).toList();
    }

    private void failTwice() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> resilientLlmGateway.explain("system", List.of(), "질문"))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
