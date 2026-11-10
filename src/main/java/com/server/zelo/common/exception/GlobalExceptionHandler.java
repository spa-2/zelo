package com.server.zelo.common.exception;
import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j(topic = "service")
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final BusinessMetrics businessMetrics;

    // 요청 횟수 제한(로그인 시도, 메일 발송 등), 429와 함께 Retry-After 헤더를 내려줌
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimitExceededException(RateLimitExceededException e) {
        HttpHeaders headers = new HttpHeaders();
        // 몇 초 후 재시도 가능한지 명요
        headers.add("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        ErrorCode code = e.getErrorCode();

        log.info("요청 횟수 제한 code={} retryAfter={}s", code.getCode(), e.getRetryAfterSeconds());
        countError(code);

        return ResponseEntity.status(code.getStatus())
                .headers(headers)
                .body(ApiResponse.error(code, e.getMessage()));
    }

    /**
     * 비즈니스 예외를 해당 오류 코드의 HTTP 상태와 예외 메시지를 담은 응답으로 변환한다.
     * 예외에 data가 있으면 함께 담고, 없으면 data는 null이다.
     *
     * @param e 처리할 비즈니스 예외
     * @return 오류 코드의 HTTP 상태, 응답 코드와 예외 메시지(와 data)를 담은 오류 응답
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusinessException(BusinessException e) {
        ErrorCode code = e.getErrorCode();

        if (code.getStatus().is5xxServerError()) {
            log.warn("비즈니스 예외 code={} message={}", code.getCode(), e.getMessage());
        } else {
            log.info("비즈니스 예외 code={} message={}", code.getCode(), e.getMessage());
        }
        countError(code);

        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.error(code, e.getMessage(), e.getData()));
    }

    /**
     * 필드 검증 오류를 "필드명: 메시지" 형식으로 쉼표로 연결해 잘못된 입력 응답을 만든다.
     * 필드 오류가 없으면 기본 입력 오류 메시지를 사용한다.
     *
     * @param e 요청 인자 검증 중 발생한 예외
     * @return HTTP 400 상태와 {@link ErrorCode#INVALID_INPUT} 코드를 담은 오류 응답
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("입력값이 올바르지 않습니다");

        log.info("입력값 검증 실패 {}", message);
        countError(ErrorCode.INVALID_INPUT);

        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, message));
    }

    /**
     * 누락되었거나 읽을 수 없는 요청 본문을 기본 입력 오류 응답으로 변환한다.
     *
     * @param e 요청 본문을 읽는 중 발생한 예외
     * @return HTTP 400 상태와 {@link ErrorCode#INVALID_INPUT}의 기본 메시지를 담은 오류 응답
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.info("body값 누락");
        countError(ErrorCode.INVALID_INPUT);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT));
    }

    /**
     * 경로 변수나 요청 파라미터의 타입 변환 실패를 입력 오류 응답으로 변환한다.
     *
     * @param e 요청 인자의 타입 변환 중 발생한 예외
     * @return HTTP 400 상태와 {@link ErrorCode#INVALID_INPUT} 코드를 담은 오류 응답
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.info("요청 인자 타입 변환 실패: name={}, requiredType={}", e.getName(), e.getRequiredType());
        countError(ErrorCode.INVALID_INPUT);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT));
    }

    /**
     * Redis에 연결하지 못하거나 응답이 늦어진 경우를 일시적 장애로 보고 재시도를 안내하는 응답으로 변환한다.
     * 명령 오류까지 장애로 오인하지 않도록 접속과 타임아웃 예외만 지정한다.
     *
     * @param e Redis 접속 실패 또는 타임아웃 예외
     * @return HTTP 503 상태와 {@link ErrorCode#SERVICE_UNAVAILABLE}의 기본 메시지를 담은 오류 응답
     */
    @ExceptionHandler({RedisConnectionFailureException.class, RedisConnectionException.class, RedisTimeoutException.class})
    public ResponseEntity<ApiResponse<Void>> handleRedisUnavailable(Exception e) {
        log.error("Redis 장애 발생", e);
        countError(ErrorCode.SERVICE_UNAVAILABLE);
        return ResponseEntity.status(ErrorCode.SERVICE_UNAVAILABLE.getStatus())
                .body(ApiResponse.error(ErrorCode.SERVICE_UNAVAILABLE));
    }

    // 스캐너가 없는 경로(/wp-login.php 등)를 찔러도 서버 오류(500)로 집계되지 않게 404로 응답한다
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        log.info("없는 경로 요청 path={}", e.getResourcePath());
        countError(ErrorCode.NOT_FOUND);
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getStatus())
                .body(ApiResponse.error(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.info("지원하지 않는 요청 방식 method={}", e.getMethod());
        countError(ErrorCode.METHOD_NOT_ALLOWED);
        // 405 응답은 허용된 메서드를 Allow 헤더로 알려야 한다
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .headers(e.getHeaders())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED));
    }

    /**
     * 처리되지 않은 예외를 로그에 기록하고 내부 예외 내용 없이 기본 서버 오류 응답을 반환한다.
     *
     * @param e 처리되지 않은 예외
     * @return HTTP 500 상태와 {@link ErrorCode#INTERNAL_ERROR}의 기본 메시지를 담은 오류 응답
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("서버 오류 발생", e);
        countError(ErrorCode.INTERNAL_ERROR);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
    }

    // 서비스 코드 오류마다 지표를 세지 않고 모든 오류 응답을 여기서 센다.
    private void countError(ErrorCode code) {
        businessMetrics.count(BusinessMetric.API_ERROR, "code", code.getCode());
    }
}
