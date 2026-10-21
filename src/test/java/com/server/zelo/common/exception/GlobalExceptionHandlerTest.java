package com.server.zelo.common.exception;

import com.server.zelo.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisException;
import org.redisson.client.RedisTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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
}
