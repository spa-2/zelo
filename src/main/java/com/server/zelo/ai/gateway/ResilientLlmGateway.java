package com.server.zelo.ai.gateway;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.ai.prompt.QuoteExplainPrompt;
import com.server.zelo.ai.prompt.ReviewSummaryPrompt;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.product.entity.BaseUnit;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

// 실제 구현(real/fake)을 감싸 시도당 시간 제한, 재시도, 서킷 브레이커를 건다.
// LlmGateway를 주입받는 쪽은 모두 이 클래스를 받으므로, 이후 추가되는 LLM 기능도 자동으로 보호된다
@Slf4j
@Primary
@Component
public class ResilientLlmGateway implements LlmGateway {

    // 감싸는 대상(real/fake 구현)을 이 이름으로 등록해, 이 클래스가 자기 자신을 주입받지 않게 한다
    public static final String DELEGATE = "llmDelegate";
    // [모니터링] 지표의 outcome 태그 값. 실패는 오류 코드(AI_002 등)를 그대로 쓴다
    private static final String SUCCESS = "SUCCESS";
    // [모니터링] zelo.llm.time.budget.exceeded의 stage 태그 값: 시도하지 않음 / 남은 시간에 맞춰 짧게 기다리다 끊음
    private static final String SKIPPED = "SKIPPED";
    private static final String CUT = "CUT";

    private final Retry retry;
    private final String model;
    // 시도당 시간 제한(zelo.llm.timeout). 메시지에 남은 시간이 이보다 적을 때만 더 짧게 기다린다
    private final Duration timeout;
    private final String reviewSummaryModel;
    private final LlmGateway llmGateway;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    // [모니터링] zelo.llm.call, zelo.llm.attempt, zelo.llm.tokens 지표를 남기는 데만 쓴다
    private final BusinessMetrics businessMetrics;
    private final LlmTimeBudget llmTimeBudget;
    private final ExecutorService executorService;
    // [모니터링] explain 호출을 QUOTE_EXPLAIN과 CHAT_ANSWER로 나눠 기록하는 데만 쓴다 (explainOperation)
    private final QuoteExplainPrompt quoteExplainPrompt;

    public ResilientLlmGateway(
            Retry retry,
            // 실패해서 응답이 없으면 어떤 모델에 보냈는지 알 수 없어, 로그에는 설정한 모델 이름을 남긴다 (리뷰 요약은 따로 설정한 모델)
            @Value("${spring.ai.openai.chat.model}") String model,
            @Value("${zelo.llm.review-summary.model}") String reviewSummaryModel,
            @Qualifier(DELEGATE) LlmGateway llmGateway,
            TimeLimiter timeLimiter,
            CircuitBreaker circuitBreaker,
            BusinessMetrics businessMetrics,
            LlmTimeBudget llmTimeBudget,
            ExecutorService executorService,
            QuoteExplainPrompt quoteExplainPrompt) {
        this.retry = retry;
        this.model = model;
        this.reviewSummaryModel = reviewSummaryModel;
        this.llmGateway = llmGateway;
        this.timeLimiter = timeLimiter;
        this.timeout = timeLimiter.getTimeLimiterConfig().getTimeoutDuration();
        this.circuitBreaker = circuitBreaker;
        this.businessMetrics = businessMetrics;
        this.llmTimeBudget = llmTimeBudget;
        this.executorService = executorService;
        this.quoteExplainPrompt = quoteExplainPrompt;
    }

    @Override
    public LlmReply explain(String systemPrompt, List<ChatMessage> history, String userText) {
        return call(explainOperation(systemPrompt), model,
                () -> withUsage(requireText(llmGateway.explain(systemPrompt, history, userText)))).content();
    }

    // [모니터링] explain은 견적 안내와 일반 답변이 함께 쓴다. 프롬프트가 달라 걸리는 시간·토큰도 달라서, 견적 안내 프롬프트인지로 나눠 기록한다
    private LlmOperation explainOperation(String systemPrompt) {
        return quoteExplainPrompt.systemPrompt().equals(systemPrompt) ? LlmOperation.QUOTE_EXPLAIN : LlmOperation.CHAT_ANSWER;
    }

    @Override
    public LlmResult<MenuPlan> planMenu(String systemPrompt, List<ChatMessage> history, String userText,
                                        Map<String, BaseUnit> ingredientUnits) {
        return call(LlmOperation.MENU_PLAN, model, () -> requireValidPlan(
                llmGateway.planMenu(systemPrompt, history, userText, ingredientUnits), ingredientUnits));
    }

    // 수정 계획만 받고 견적은 고치지 않으므로, 재시도해도 견적이 두 번 바뀌지 않는다
    @Override
    public LlmResult<QuoteEditPlan> planQuoteEdit(String systemPrompt, List<ChatMessage> history, String userText,
                                                  Map<String, BaseUnit> ingredientUnits) {
        return call(LlmOperation.QUOTE_EDIT_PLAN, model, () -> requireValidEditPlan(
                llmGateway.planQuoteEdit(systemPrompt, history, userText, ingredientUnits), ingredientUnits));
    }

    @Override
    public LlmReply summarizeReviews(String systemPrompt, String reviewText) {
        return call(LlmOperation.REVIEW_SUMMARY, reviewSummaryModel, () -> withUsage(requireSummary(llmGateway.summarizeReviews(systemPrompt, reviewText)))).content();
    }

    // 재시도(바깥) → 서킷 브레이커 → 시도당 시간 제한(안쪽) 순서로 감싼다.
    // 그래서 재시도한 시도도 서킷 브레이커가 한 번씩 세고, 서킷이 열려 막힌 호출은 재시도하지 않는다
    // 지표는 두 단계로 남긴다: 시도 1번(zelo.llm.attempt)과, 재시도까지 끝난 호출 1건(zelo.llm.call = 사용자가 실제로 기다린 시간)
    // 채팅 메시지 안의 호출이면 시도마다 메시지에 남은 시간(LlmTimeBudget)을 보고 기다릴 시간을 정한다
    private <T> LlmResult<T> call(LlmOperation operation, String requestModel, Supplier<LlmResult<T>> request) {
        AtomicInteger attemptNumber = new AtomicInteger();
        Callable<LlmResult<T>> logged = () -> {
            // 남은 시간이 부족하면 서킷 브레이커에 닿기 전에 멈춘다. OpenAI에 보내지 않은 시도라 서킷이 세지 않게 한다
            Duration attemptTimeout = llmTimeBudget.attemptTimeout(timeout)
                    .orElseThrow(() -> skipAttempt(operation));

            // (가) 시도 1번 = 다른 스레드에서 실행하고, 정한 시간만 기다린다.
            Callable<LlmResult<T>> attempt = () -> limitTime(operation, attemptTimeout,
                    () -> executorService.submit(() -> request.get()));

            // (나) 그 시도를 서킷 브레이커로 감싼다.
            Callable<LlmResult<T>> guarded = CircuitBreaker.decorateCallable(circuitBreaker, attempt);

            // 재시도 안쪽·서킷 바깥에서 기록해, 시도마다 한 줄씩 남고 서킷에 막힌 호출도 남는다
            return logAttempt(operation, requestModel, attemptNumber.incrementAndGet(), guarded);
        };
        long start = System.nanoTime(); // [모니터링] zelo.llm.call 시간 측정 시작
        try {
            // (다) 그걸 다시 재시도로 감싸서 실행한다.
            LlmResult<T> result = Retry.decorateCallable(retry, logged).call();
            recordElapsed(BusinessMetric.LLM_CALL, operation, start, SUCCESS); // [모니터링]
            return result;
        } catch (Exception e) {
            // (라) 끝내 실패하면 우리 프로젝트 예외로 바꿔 던진다.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 시도마다 한 줄 로그는 이미 남겼다. BusinessException에는 원인 예외를 담을 수 없어,
            // 알 수 없는 오류만 원인을 찾을 수 있게 여기서 스택을 남긴다
            ErrorCode errorCode = LlmErrorClassifier.classify(e);
            recordElapsed(BusinessMetric.LLM_CALL, operation, start, errorCode.getCode()); // [모니터링]
            if (errorCode == ErrorCode.AI_RESPONSE_FAILED) {
                log.warn("LLM 호출 실패 원인: {}", errorCode.getCode(), e);
            }
            throw new BusinessException(errorCode);
        }
    }

    // 시도당 제한 그대로 기다리면 빈으로 등록한 TimeLimiter를 써서 resilience4j 시간 초과 지표에 남긴다.
    // 메시지에 남은 시간이 더 짧으면 같은 설정에 시간만 바꾼 TimeLimiter로 기다리고, 그 시간 초과는 예산 초과로 바꾼다.
    // 시도당 제한을 다 기다리지 못하고 끊은 것은 OpenAI 장애의 증거가 아니라서 서킷 브레이커가 세지 않게 하기 위해서다
    private <T> LlmResult<T> limitTime(LlmOperation operation, Duration attemptTimeout,
                                       Supplier<Future<LlmResult<T>>> attempt) throws Exception {
        if (attemptTimeout.equals(timeout)) {
            return timeLimiter.executeFutureSupplier(attempt);
        }
        TimeLimiter shortened = TimeLimiter.of(TimeLimiterConfig.from(timeLimiter.getTimeLimiterConfig())
                .timeoutDuration(attemptTimeout)
                .build());
        try {
            return shortened.executeFutureSupplier(attempt);
        } catch (TimeoutException e) {
            businessMetrics.count(BusinessMetric.LLM_TIME_BUDGET_EXCEEDED, "operation", operation.name(), "stage", CUT); // [모니터링]
            throw new LlmTimeBudget.ExceededException("메시지 시간 예산 안에 답하지 않음: " + attemptTimeout.toMillis() + "ms");
        }
    }

    // 남은 시간이 시도 최소 시간보다 적어 OpenAI에 보내지 않는다. 보내지 않았으므로 시도 지표(zelo.llm.attempt)에는 남기지 않는다
    private LlmTimeBudget.ExceededException skipAttempt(LlmOperation operation) {
        log.warn("LLM 시도 생략: 메시지 시간 예산 부족 remainingMs={} operation={}",
                llmTimeBudget.remaining().map(Duration::toMillis).orElse(0L), operation);
        businessMetrics.count(BusinessMetric.LLM_TIME_BUDGET_EXCEEDED, "operation", operation.name(), "stage", SKIPPED); // [모니터링]
        return new LlmTimeBudget.ExceededException("메시지 시간 예산 부족으로 시도하지 않음");
    }

    // 프롬프트·사용자 메시지 원문은 남기지 않는다
    // 서킷에 막힌 시도(AI_008)도 시도 지표에 남아, 막힌 호출 수를 operation별로 볼 수 있다
    private <T> LlmResult<T> logAttempt(LlmOperation operation, String requestModel, int attemptNumber,
                                        Callable<LlmResult<T>> guarded) throws Exception {
        long start = System.nanoTime();
        try {
            LlmResult<T> result = guarded.call();
            log.info("LLM 호출 attempt={} model={} latencyMs={} success=true error=- promptTokens={} completionTokens={} operation={}",
                    attemptNumber, orDash(result.model()), elapsedMillis(start),
                    orDash(result.promptTokens()), orDash(result.completionTokens()), operation);
            recordElapsed(BusinessMetric.LLM_ATTEMPT, operation, start, SUCCESS); // [모니터링]
            countTokens(operation, result); // [모니터링]
            return result;
        } catch (Exception e) {
            String errorCode = LlmErrorClassifier.classify(e).getCode();
            log.warn("LLM 호출 attempt={} model={} latencyMs={} success=false error={} promptTokens=- completionTokens=- operation={}",
                    attemptNumber, requestModel, elapsedMillis(start), errorCode, operation);
            recordElapsed(BusinessMetric.LLM_ATTEMPT, operation, start, errorCode); // [모니터링]
            throw e;
        }
    }

    // [모니터링] 걸린 시간을 operation·outcome 태그와 함께 타이머 지표로 남긴다
    private void recordElapsed(BusinessMetric metric, LlmOperation operation, long startNanos, String outcome) {
        businessMetrics.record(metric, Duration.ofNanos(System.nanoTime() - startNanos),
                "operation", operation.name(), "outcome", outcome);
    }

    // [모니터링] 비용은 토큰 수 × 모델 단가라서 모델도 태그로 남긴다. 단가는 바뀔 수 있어 코드가 아니라 대시보드에서 곱한다.
    // 시간 초과로 끊긴 시도는 응답이 없어 토큰을 알 수 없으므로, 그 수는 zelo.llm.attempt의 AI_002로 따로 본다
    private void countTokens(LlmOperation operation, LlmResult<?> result) {
        String model = result.model() == null ? "unknown" : result.model();
        if (result.promptTokens() != null) {
            businessMetrics.count(BusinessMetric.LLM_TOKENS, result.promptTokens(),
                    "operation", operation.name(), "model", model, "type", "prompt");
        }
        if (result.completionTokens() != null) {
            businessMetrics.count(BusinessMetric.LLM_TOKENS, result.completionTokens(),
                    "operation", operation.name(), "model", model, "type", "completion");
        }
    }

    private LlmReply requireText(LlmReply reply) {
        if (reply == null || !StringUtils.hasText(reply.text())) {
            // 호출은 성공했지만 답이 비어 있으면 서킷 브레이커가 실패로 세도록 예외로 바꾼다
            throw new BusinessException(ErrorCode.AI_EMPTY_RESPONSE);
        }
        return reply;
    }

    // explain도 같은 보호와 기록을 거치도록, 모델·토큰을 LlmResult에 옮겨 담는다
    private static LlmResult<LlmReply> withUsage(LlmReply reply) {
        return LlmResult.of(reply, reply.model(), reply.promptTokens(), reply.completionTokens());
    }

    // LLM이 준 값은 믿지 않는다. 시도 안에서 검증해야 검증 실패도 형식 오류처럼 한 번 더 물어볼 수 있다
    private LlmResult<MenuPlan> requireValidPlan(LlmResult<MenuPlan> result, Map<String, BaseUnit> ingredientUnits) {
        if (result == null || result.content() == null) {
            // 응답 본문이 비어 객체를 만들지 못한 경우. 문장 응답의 빈 답과 같게 본다
            throw new BusinessException(ErrorCode.AI_EMPTY_RESPONSE);
        }
        result.content().validate(ingredientUnits);
        return result;
    }

    // 메뉴 계획과 같은 규칙: 빈 답은 AI_EMPTY_RESPONSE, 검증 실패는 시도 안에서 AI_INVALID_RESPONSE로 바꿔 한 번 더 묻는다
    private LlmResult<QuoteEditPlan> requireValidEditPlan(LlmResult<QuoteEditPlan> result,
                                                          Map<String, BaseUnit> ingredientUnits) {
        if (result == null || result.content() == null) {
            throw new BusinessException(ErrorCode.AI_EMPTY_RESPONSE);
        }
        result.content().validate(ingredientUnits);
        return result;
    }

    // 요약은 한 줄로 저장하므로 줄바꿈·연속 공백을 한 칸으로 줄인 뒤 길이를 본다.
    // 너무 길면 메뉴 계획 검증 실패처럼 한 번 더 물어볼 수 있게 시도 안에서 AI_INVALID_RESPONSE로 바꾼다
    private LlmReply requireSummary(LlmReply reply) {
        requireText(reply);
        String summary = reply.text().strip().replaceAll("\\s+", " ");
        if (summary.codePointCount(0, summary.length()) > ReviewSummaryPrompt.SUMMARY_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        return LlmReply.of(summary, reply.model(), reply.promptTokens(), reply.completionTokens());
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static Object orDash(Object value) {
        return value == null ? "-" : value;
    }
}
