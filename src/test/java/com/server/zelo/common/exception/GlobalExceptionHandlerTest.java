package com.server.zelo.common.exception;
import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.monitoring.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisException;
import org.redisson.client.RedisTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new BusinessMetrics(meterRegistry));

    @Test
    void Redis_접속_실패는_503과_COMMON_004를_반환한다() {
        // when
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleRedisUnavailable(new RedisConnectionFailureException("연결 실패"));

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getCode());
    }

    @Test
    void Redisson_접속_실패는_503과_COMMON_004를_반환한다() {
        // when
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleRedisUnavailable(new RedisConnectionException("연결 실패"));

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getCode());
    }

    @Test
    void Redisson_타임아웃은_503과_COMMON_004를_반환한다() {
        // when
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleRedisUnavailable(new RedisTimeoutException("응답 지연"));

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getCode());
    }

    @Test
    void 응답에는_내부_예외_내용이_담기지_않는다() {
        // when
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleRedisUnavailable(new RedisConnectionFailureException("redis://10.0.0.5:6379 연결 실패"));

        // then
        assertThat(response.getBody().message()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getMessage());
    }

    @Test
    void Redis_명령_오류는_장애로_보지_않고_기존처럼_500을_반환한다() {
        // when
        ResponseEntity<ApiResponse<Void>> response = handler.handleException(new RedisException("WRONGTYPE"));

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
    }

    @Test
    void 로그인_시도_횟수_초과는_429와_Retry_After_헤더를_반환한다() {
        // when
        ResponseEntity<ApiResponse<Void>> response = handler.handleRateLimitExceededException(
                new RateLimitExceededException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS, 540));

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("540");
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS.getCode());
    }

    @Test
    void 비즈니스_예외는_에러_코드별로_센다() {
        // when
        handler.handleBusinessException(new BusinessException(ErrorCode.INVALID_EMAIL_CODE));
        handler.handleBusinessException(new BusinessException(ErrorCode.INVALID_EMAIL_CODE));

        // then
        assertThat(errorCount(ErrorCode.INVALID_EMAIL_CODE)).isEqualTo(2);
    }

    @Test
    void 횟수_제한_예외도_에러_코드별로_센다() {
        // when
        handler.handleRateLimitExceededException(new RateLimitExceededException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS, 540));

        // then
        assertThat(errorCount(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS)).isEqualTo(1);
    }

    @Test
    void 처리되지_않은_예외는_INTERNAL_ERROR로_센다() {
        // when
        handler.handleException(new RuntimeException("예상 못 한 오류"));

        // then
        assertThat(errorCount(ErrorCode.INTERNAL_ERROR)).isEqualTo(1);
    }

    @Test
    void Redis_장애는_SERVICE_UNAVAILABLE로_센다() {
        // when
        handler.handleRedisUnavailable(new RedisConnectionFailureException("연결 실패"));

        // then
        assertThat(errorCount(ErrorCode.SERVICE_UNAVAILABLE)).isEqualTo(1);
    }

    @Test
    void 없는_경로_요청은_500이_아니라_404와_COMMON_005를_반환한다() {
        // when
        ResponseEntity<ApiResponse<Void>> response = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "/.git/config", ".git/config"));

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
        assertThat(errorCount(ErrorCode.NOT_FOUND)).isEqualTo(1);
    }

    @Test
    void 지원하지_않는_요청_방식은_405와_COMMON_006을_반환한다() {
        // when
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("DELETE"));

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.getCode());
        assertThat(errorCount(ErrorCode.METHOD_NOT_ALLOWED)).isEqualTo(1);
    }

    private double errorCount(ErrorCode code) {
        return meterRegistry.get("zelo.api.error").tag("code", code.getCode()).counter().count();
    }
}
