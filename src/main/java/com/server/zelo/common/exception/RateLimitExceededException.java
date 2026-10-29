package com.server.zelo.common.exception;

import lombok.Getter;

// 횟수 제한에 걸렸을 때 던지고, 언제 다시 시도할 수 있는지(초)를 함께 전달한다
public class RateLimitExceededException extends BusinessException {

    @Getter
    private final long retryAfterSeconds;

    public RateLimitExceededException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode, errorCode.getMessage(), retryAfterSeconds);
        this.retryAfterSeconds = retryAfterSeconds;
    }

}
