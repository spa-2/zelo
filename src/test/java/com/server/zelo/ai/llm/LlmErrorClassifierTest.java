package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class LlmErrorClassifierTest {

    @Test
    void 시간_제한에_걸리면_AI_TIMEOUT이고_재시도한다() {
        // given
        TimeoutException exception = new TimeoutException();

        // when & then
        assertThat(LlmErrorClassifier.classify(exception)).isEqualTo(ErrorCode.AI_TIMEOUT);
        assertThat(LlmErrorClassifier.isRetryable(exception)).isTrue();
    }

    @Test
    void HTTP_타임아웃도_AI_TIMEOUT이고_다른_IO_오류는_AI_NETWORK_ERROR로_재시도한다() {
        // when & then
        assertThat(LlmErrorClassifier.classify(OpenAiErrors.httpTimeout())).isEqualTo(ErrorCode.AI_TIMEOUT);
        assertThat(LlmErrorClassifier.classify(OpenAiErrors.connectionFailed())).isEqualTo(ErrorCode.AI_NETWORK_ERROR);
        assertThat(LlmErrorClassifier.isRetryable(OpenAiErrors.connectionFailed())).isTrue();
    }

    @Test
    void 요청_한도_초과_429는_AI_RATE_LIMITED이고_재시도한다() {
        // when & then
        assertThat(LlmErrorClassifier.classify(OpenAiErrors.rateLimit())).isEqualTo(ErrorCode.AI_RATE_LIMITED);
        assertThat(LlmErrorClassifier.isRetryable(OpenAiErrors.rateLimit())).isTrue();
    }

    @Test
    void 서버_오류_5xx는_AI_SERVER_ERROR이고_재시도한다() {
        // when & then
        assertThat(LlmErrorClassifier.classify(OpenAiErrors.serverError(500))).isEqualTo(ErrorCode.AI_SERVER_ERROR);
        assertThat(LlmErrorClassifier.classify(OpenAiErrors.serverError(503))).isEqualTo(ErrorCode.AI_SERVER_ERROR);
        assertThat(LlmErrorClassifier.isRetryable(OpenAiErrors.serverError(500))).isTrue();
    }

    @Test
    void 요청_오류_4xx는_AI_REQUEST_REJECTED이고_다시_보내도_똑같이_실패하므로_재시도하지_않는다() {
        // when & then
        assertThat(LlmErrorClassifier.classify(OpenAiErrors.badRequest())).isEqualTo(ErrorCode.AI_REQUEST_REJECTED);
        assertThat(LlmErrorClassifier.classify(OpenAiErrors.unauthorized())).isEqualTo(ErrorCode.AI_REQUEST_REJECTED);
        assertThat(LlmErrorClassifier.isRetryable(OpenAiErrors.badRequest())).isFalse();
    }

    @Test
    void 요청_거절_4xx만_서킷_실패로_세지_않고_나머지_장애는_센다() {
        // when & then: 요청 자체의 문제는 OpenAI 장애가 아니다
        assertThat(LlmErrorClassifier.isCircuitFailure(OpenAiErrors.badRequest())).isFalse();
        assertThat(LlmErrorClassifier.isCircuitFailure(OpenAiErrors.unauthorized())).isFalse();
        // 429는 4xx지만 OpenAI가 바쁘다는 뜻이라 센다
        assertThat(LlmErrorClassifier.isCircuitFailure(OpenAiErrors.rateLimit())).isTrue();
        assertThat(LlmErrorClassifier.isCircuitFailure(OpenAiErrors.serverError(500))).isTrue();
        assertThat(LlmErrorClassifier.isCircuitFailure(new TimeoutException())).isTrue();
        assertThat(LlmErrorClassifier.isCircuitFailure(OpenAiErrors.connectionFailed())).isTrue();
        assertThat(LlmErrorClassifier.isCircuitFailure(new BusinessException(ErrorCode.AI_EMPTY_RESPONSE))).isTrue();
    }

    @Test
    void 서킷이_열려_막힌_호출과_빈_응답은_재시도하지_않는다() {
        // given
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");
        circuitBreaker.transitionToOpenState();
        CallNotPermittedException notPermitted = CallNotPermittedException.createCallNotPermittedException(circuitBreaker);
        BusinessException empty = new BusinessException(ErrorCode.AI_EMPTY_RESPONSE);

        // when & then
        assertThat(LlmErrorClassifier.classify(notPermitted)).isEqualTo(ErrorCode.AI_CIRCUIT_OPEN);
        assertThat(LlmErrorClassifier.classify(empty)).isEqualTo(ErrorCode.AI_EMPTY_RESPONSE);
        assertThat(LlmErrorClassifier.isRetryable(notPermitted)).isFalse();
        assertThat(LlmErrorClassifier.isRetryable(empty)).isFalse();
    }

    @Test
    void 감싸진_예외는_알아보지_않고_AI_RESPONSE_FAILED로_재시도하지_않는다() {
        // given: Spring AI는 감싸지 않지만, 감싸서 오면 모르는 오류로 처리되는 것을 고정해 둔다
        RuntimeException wrapped = new RuntimeException("wrapped", OpenAiErrors.serverError(502));

        // when & then
        assertThat(LlmErrorClassifier.classify(wrapped)).isEqualTo(ErrorCode.AI_RESPONSE_FAILED);
        assertThat(LlmErrorClassifier.isRetryable(wrapped)).isFalse();
    }

    @Test
    void 알_수_없는_예외는_AI_RESPONSE_FAILED이고_재시도하지_않는다() {
        // given
        IllegalStateException exception = new IllegalStateException("bug");

        // when & then
        assertThat(LlmErrorClassifier.classify(exception)).isEqualTo(ErrorCode.AI_RESPONSE_FAILED);
        assertThat(LlmErrorClassifier.isRetryable(exception)).isFalse();
    }
}
