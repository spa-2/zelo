package com.server.zelo.ai.llm;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final LlmGateway llmGateway;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final ExecutorService executorService;

    public ResilientLlmGateway(
            Retry retry,
            @Qualifier(DELEGATE) LlmGateway llmGateway,
            TimeLimiter timeLimiter,
            CircuitBreaker circuitBreaker,
            ExecutorService executorService) {
        this.retry = retry;
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
        try {
            // (다) 그걸 다시 재시도로 감싸서 실행한다.
            return Retry.decorateCallable(retry, guarded).call();
        } catch (Exception e) {
            // (라) 끝내 실패하면 우리 프로젝트 예외로 바꿔 던진다.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // BusinessException에는 원인 예외를 담을 수 없어 원인은 여기서 로그로 남긴다.
            // 종류를 알아본 오류는 한 줄로, 알 수 없는 오류만 원인을 찾을 수 있게 스택까지 남긴다
            ErrorCode errorCode = LlmErrorClassifier.classify(e);
            if (errorCode == ErrorCode.AI_RESPONSE_FAILED) {
                log.warn("LLM 호출 실패: {}", errorCode.getCode(), e);
            } else {
                log.warn("LLM 호출 실패: {} ({})", errorCode.getCode(), e.toString());
            }
            throw new BusinessException(errorCode);
        }
    }

    private LlmReply requireText(LlmReply reply) {
        if (reply == null || !StringUtils.hasText(reply.text())) {
            // 호출은 성공했지만 답이 비어 있으면 서킷 브레이커가 실패로 세도록 예외로 바꾼다
            throw new BusinessException(ErrorCode.AI_EMPTY_RESPONSE);
        }
        return reply;
    }
}
