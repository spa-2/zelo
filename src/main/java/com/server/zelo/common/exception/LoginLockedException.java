package com.server.zelo.common.exception;

import lombok.Getter;

public class LoginLockedException extends BusinessException {

    @Getter
    private final long retryAfterSeconds;

    public LoginLockedException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode, errorCode.getMessage(), retryAfterSeconds);
        this.retryAfterSeconds = retryAfterSeconds;
    }

}
