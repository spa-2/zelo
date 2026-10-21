package com.server.zelo.ai.llm;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.io.InterruptedIOException;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;

// 다시 보내면 나을 수 있는 오류(타임아웃·429·5xx·네트워크)만 재시도한다. 4xx는 다시 보내도 똑같이 실패한다
// OpenAI가 보내주는 예외가 우리 ErrorCode로 치면 무엇인가를 내타내는 클래스
public final class LlmErrorClassifier {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR_MIN = 500;

    private static final Set<ErrorCode> RETRYABLE = Set.of(
            ErrorCode.AI_TIMEOUT,
            ErrorCode.AI_RATE_LIMITED,
            ErrorCode.AI_SERVER_ERROR,
            ErrorCode.AI_NETWORK_ERROR,
            // 답이 형식·검증에 맞지 않음: 같은 질문을 다시 하면 맞게 답할 수 있어 한 번 더 물어본다
            ErrorCode.AI_INVALID_RESPONSE);

    private LlmErrorClassifier() {
    }

    // 위 4가지 오류 중 하나면 재시도
    public static boolean isRetryable(Throwable throwable) {
        return RETRYABLE.contains(classify(throwable));
    }

    // 요청 자체가 잘못된 4xx는 OpenAI 장애가 아니므로 서킷 실패로 세지 않는다.
    // 세면 한 사용자의 잘못된 요청이 반복될 때 서킷이 열려 다른 사용자의 정상 요청까지 막힌다
    public static boolean isCircuitFailure(Throwable throwable) {
        return classify(throwable) != ErrorCode.AI_REQUEST_REJECTED;
    }

    // LLM이 보낸 예외를 우리 ErrorCode로 바꿈
    public static ErrorCode classify(Throwable throwable) {
        return switch (throwable) {
            case BusinessException e -> e.getErrorCode();
            case TimeoutException ignored -> ErrorCode.AI_TIMEOUT;
            case CallNotPermittedException ignored -> ErrorCode.AI_CIRCUIT_OPEN;
            // 구조화 출력(MenuPlan 등)에서 LLM이 준 JSON을 객체로 바꾸지 못하면 Spring AI가 Jackson 예외를 그대로 던진다
            case JacksonException ignored -> ErrorCode.AI_INVALID_RESPONSE;
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
