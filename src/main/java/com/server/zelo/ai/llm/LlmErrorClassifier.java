package com.server.zelo.ai.llm;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.io.InterruptedIOException;
import java.util.Set;
import java.util.concurrent.TimeoutException;

// 다시 보내면 나을 수 있는 오류(타임아웃·429·5xx·네트워크)만 재시도한다. 4xx는 다시 보내도 똑같이 실패한다
// OpenAI가 보내주는 예외가 우리 ErrorCode로 치면 무엇인가를 내타내는 클래스
public final class LlmErrorClassifier {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR_MIN = 500;

    private static final Set<ErrorCode> RETRYABLE = Set.of(
            ErrorCode.AI_TIMEOUT,
            ErrorCode.AI_RATE_LIMITED,
            ErrorCode.AI_SERVER_ERROR,
            ErrorCode.AI_NETWORK_ERROR);

    private LlmErrorClassifier() {
    }

    // 위 4가지 오류 중 하나면 재시도
    public static boolean isRetryable(Throwable throwable) {
        return RETRYABLE.contains(classify(throwable));
    }

    // LLM이 보낸 예외를 우리 ErrorCode로 바꿈
    public static ErrorCode classify(Throwable throwable) {
        return switch (throwable) {
            case BusinessException e -> e.getErrorCode();
            case TimeoutException ignored -> ErrorCode.AI_TIMEOUT;
            case CallNotPermittedException ignored -> ErrorCode.AI_CIRCUIT_OPEN;
            case OpenAIServiceException e -> fromStatus(e.statusCode());
            // 응답을 받지 못하면 Spring AI가 IOException을 감싸 던진다. 원인이 InterruptedIOException이면 HTTP 타임아웃이다
            case OpenAIIoException e ->
                    e.getCause() instanceof InterruptedIOException ? ErrorCode.AI_TIMEOUT : ErrorCode.AI_NETWORK_ERROR;
            default -> ErrorCode.AI_RESPONSE_FAILED;
        };
    }

    private static ErrorCode fromStatus(int status) {
        if (status == TOO_MANY_REQUESTS) {
            return ErrorCode.AI_RATE_LIMITED;
        }
        return status >= SERVER_ERROR_MIN ? ErrorCode.AI_SERVER_ERROR : ErrorCode.AI_REQUEST_REJECTED;
    }
}
