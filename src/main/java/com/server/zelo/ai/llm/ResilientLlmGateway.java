package com.server.zelo.ai.llm;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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

    private final Retry retry;
    private final String model;
    private final LlmGateway llmGateway;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final ExecutorService executorService;

    public ResilientLlmGateway(
            Retry retry,
            // 실패해서 응답이 없으면 어떤 모델에 보냈는지 알 수 없어, 로그에는 설정한 모델 이름을 남긴다
            @Value("${spring.ai.openai.chat.model}") String model,
            @Qualifier(DELEGATE) LlmGateway llmGateway,
            TimeLimiter timeLimiter,
            CircuitBreaker circuitBreaker,
            ExecutorService executorService) {
        this.retry = retry;
        this.model = model;
        this.llmGateway = llmGateway;
        this.timeLimiter = timeLimiter;
        this.circuitBreaker = circuitBreaker;
        this.executorService = executorService;
    }

    @Override
    public LlmReply explain(String systemPrompt, String userText) {
        return call(() -> llmGateway.explain(systemPrompt, userText));
    }

    // 재시도(바깥) → 서킷 브레이커 → 시도당 시간 제한(안쪽) 순서로 감싼다.
    // 그래서 재시도한 시도도 서킷 브레이커가 한 번씩 세고, 서킷이 열려 막힌 호출은 재시도하지 않는다
    private LlmReply call(Supplier<LlmReply> request) {

        // (가) 시도 1번 = 다른 스레드에서 실행하고, 5초만 기다린다.
        Callable<LlmReply> attempt = () -> timeLimiter.executeFutureSupplier(
                () -> executorService.submit(() -> requireText(request.get())));

        // (나) 그 시도를 서킷 브레이커로 감싼다.
        Callable<LlmReply> guarded = CircuitBreaker.decorateCallable(circuitBreaker, attempt);

        // 재시도 안쪽·서킷 바깥에서 기록해, 시도마다 한 줄씩 남고 서킷에 막힌 호출도 남는다
        AtomicInteger attemptNumber = new AtomicInteger();
        Callable<LlmReply> logged = () -> logAttempt(attemptNumber.incrementAndGet(), guarded);
        try {
            // (다) 그걸 다시 재시도로 감싸서 실행한다.
            return Retry.decorateCallable(retry, logged).call();
        } catch (Exception e) {
            // (라) 끝내 실패하면 우리 프로젝트 예외로 바꿔 던진다.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 시도마다 한 줄 로그는 이미 남겼다. BusinessException에는 원인 예외를 담을 수 없어,
            // 알 수 없는 오류만 원인을 찾을 수 있게 여기서 스택을 남긴다
            ErrorCode errorCode = LlmErrorClassifier.classify(e);
            if (errorCode == ErrorCode.AI_RESPONSE_FAILED) {
                log.warn("LLM 호출 실패 원인: {}", errorCode.getCode(), e);
            }
            throw new BusinessException(errorCode);
        }
    }

    // 프롬프트·사용자 메시지 원문은 남기지 않는다
    private LlmReply logAttempt(int attemptNumber, Callable<LlmReply> guarded) throws Exception {
        long start = System.nanoTime();
        try {
            LlmReply reply = guarded.call();
            log.info("LLM 호출 attempt={} model={} latencyMs={} success=true error=- promptTokens={} completionTokens={}",
                    attemptNumber, orDash(reply.model()), elapsedMillis(start),
                    orDash(reply.promptTokens()), orDash(reply.completionTokens()));
            return reply;
        } catch (Exception e) {
            log.warn("LLM 호출 attempt={} model={} latencyMs={} success=false error={} promptTokens=- completionTokens=-",
                    attemptNumber, model, elapsedMillis(start), LlmErrorClassifier.classify(e).getCode());
            throw e;
        }
    }

    private LlmReply requireText(LlmReply reply) {
        if (reply == null || !StringUtils.hasText(reply.text())) {
            // 호출은 성공했지만 답이 비어 있으면 서킷 브레이커가 실패로 세도록 예외로 바꾼다
            throw new BusinessException(ErrorCode.AI_EMPTY_RESPONSE);
        }
        return reply;
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static Object orDash(Object value) {
        return value == null ? "-" : value;
    }
}
